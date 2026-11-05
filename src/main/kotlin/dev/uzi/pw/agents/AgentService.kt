/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import dev.uzi.pw.core.PwConfigErrors
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.ThreadingAssertions
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Runs the agent CLIs (PLAN 9.1). One run at a time; output goes to the console shown in the Agents tab.
 * While an agent runs, new `specs/` Markdown and `*.spec.ts` files under the working directory are opened,
 * and (for heal) the contents of files the run changes are captured before the change.
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project, val scope: CoroutineScope) : Disposable {

    fun interface StateListener {
        fun stateChanged(running: Boolean)
    }

    /** What the caller of [runAgent] wants beyond the default. */
    class RunOptions(
        /** Capture the contents of files changed during the run (heal). */
        val trackChanges: Boolean = false,
        /** Contents taken before the run, path → text. */
        val snapshot: Map<String, String> = emptyMap(),
        /** Called when the run ends, on a background thread. */
        val onFinished: (suspend (RunResult) -> Unit)? = null,
    )

    /**
     * [before] maps a path to its text before the run (only when tracking changes). [changed] are files whose content
     * changed or that were deleted during the run, [created] files that appeared.
     */
    class RunResult(
        val exitCode: Int,
        val stopped: Boolean,
        val before: Map<String, String>,
        val changed: Set<String>,
        val created: Set<String>,
    )

    private val busy = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<StateListener>()

    @Volatile
    private var handler: KillableColoredProcessHandler? = null

    @Volatile
    private var stopRequested = false

    private var consoleView: ConsoleView? = null

    /** The agents console. Created on first use; call on the EDT. */
    val console: ConsoleView
        get() {
            ThreadingAssertions.assertEventDispatchThread()
            return consoleView ?: NodeRunner.getInstance(project).createConsole().also {
                consoleView = it
                Disposer.register(this, it)
            }
        }

    val isRunning: Boolean get() = busy.get()

    fun addStateListener(listener: StateListener, parentDisposable: Disposable) {
        listeners += listener
        Disposer.register(parentDisposable) { listeners -= listener }
    }

    private fun fireState(running: Boolean) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) listeners.forEach { it.stateChanged(running) }
        }, ModalityState.any())
    }

    /** The supported config whose working directory contains [file], or the selected supported config. */
    fun configFor(file: VirtualFile?): PwConfig? {
        val locator = PlaywrightProjectLocator.getInstance(project)
        val selected = locator.selectedConfig()?.takeIf { it.isSupported }
        if (file == null) return selected
        if (selected != null && VfsUtilCore.isAncestor(selected.workingDir, file, false)) return selected
        return locator.configs()
            .filter { it.isSupported && VfsUtilCore.isAncestor(it.workingDir, file, false) }
            .maxByOrNull { it.workingDir.path.length } ?: selected
    }

    /** Stops the current run (agent or init-agents). */
    fun stop() {
        val h = handler ?: return
        stopRequested = true
        h.destroyProcess()
    }

    override fun dispose() {
        handler?.takeIf { !it.isProcessTerminated }?.destroyProcess()
    }

    /**
     * Starts an agent run. Returns false when another run is active. Call from any thread; the work happens in [scope].
     * [task] is the agent-neutral task text; the backend wraps it into its own prompt.
     */
    fun runAgent(kind: AgentKind, task: String, config: PwConfig, options: RunOptions = RunOptions()): Boolean {
        if (!busy.compareAndSet(false, true)) {
            PwNotifications.warn(project, PwBundle.message("agents.alreadyRunning"))
            return false
        }
        stopRequested = false
        fireState(true)
        scope.launch {
            try {
                doRunAgent(kind, task, config, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Agent run failed", e)
                PwNotifications.error(project, PwBundle.message("agents.runFailed", e.message ?: e.javaClass.simpleName))
            } finally {
                handler = null
                busy.set(false)
                fireState(false)
            }
        }
        return true
    }

    /**
     * Runs a plain command (such as `init-agents`) with its raw output in the agents console.
     * Returns false when another run is active. [onFinished] gets the exit code.
     */
    fun runCommand(title: String, cmd: GeneralCommandLine, onFinished: suspend (Int) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) {
            PwNotifications.warn(project, PwBundle.message("agents.alreadyRunning"))
            return false
        }
        stopRequested = false
        fireState(true)
        scope.launch {
            try {
                val console = withContext(Dispatchers.EDT) { showConsole().also { it.clear() } }
                console.print("$title\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                val exit = CompletableDeferred<Int>()
                val output = StringBuilder()
                val listener = object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        synchronized(output) {
                            output.append(event.text)
                            if (output.length > OUTPUT_TAIL) output.delete(0, output.length - OUTPUT_TAIL)
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        exit.complete(event.exitCode)
                    }
                }
                val h = withContext(Dispatchers.IO) { NodeRunner.getInstance(project).start(cmd, console, listener) }
                handler = h
                closeInput(h)
                val code = awaitExit(h, exit)
                console.print(exitMessage(code) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                if (code != 0) {
                    val text = synchronized(output) { output.toString() }
                    PwConfigErrors.notifyIfConfigLoadFailed(project, PlaywrightProjectLocator.getInstance(project).selectedConfig(), text)
                }
                onFinished(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Command failed: $title", e)
                PwNotifications.error(project, PwBundle.message("agents.runFailed", e.message ?: e.javaClass.simpleName))
            } finally {
                handler = null
                busy.set(false)
                fireState(false)
            }
        }
        return true
    }

    private suspend fun doRunAgent(kind: AgentKind, task: String, config: PwConfig, options: RunOptions) {
        val backend = AgentBackends.selected(project) ?: throw PwException(PwBundle.message("agents.unavailable.mcpDirect"))
        val availability = withContext(Dispatchers.IO) { backend.isAvailable(config) }
        if (!availability.ok) {
            PwNotifications.warn(project, PwBundle.message("agents.notAvailable", backend.displayName, availability.reason ?: ""))
            return
        }
        val prompt = backend.prompt(kind, task)
        val cmd = withContext(Dispatchers.IO) { backend.commandLine(kind, prompt, config) }

        val openKinds = kind != AgentKind.HEALER
        val watcher = RunWatcher(config, options.trackChanges, openKinds)
        val result = try {
            val console = withContext(Dispatchers.EDT) {
                writeIntentReadAction { FileDocumentManager.getInstance().saveAllDocuments() }
                showConsole().also { it.clear() }
            }
            options.snapshot.forEach { (path, text) -> watcher.before.putIfAbsent(path, text) }
            if (options.trackChanges) watcher.snapshotOpenDocuments()

            console.print(PwBundle.message("agents.console.header", kind.agentName, backend.displayName) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            console.print(prompt + "\n\n", ConsoleViewContentType.USER_INPUT)

            val exit = CompletableDeferred<Int>()
            val output = AgentOutputListener(backend, console, exit)
            val h = withContext(Dispatchers.IO) { NodeRunner.getInstance(project).start(cmd, null, output) }
            handler = h
            closeInput(h)

            val refresher = scope.launch {
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    watcher.refresh()
                }
            }
            val code = try {
                awaitExit(h, exit)
            } finally {
                refresher.cancel()
            }
            output.flush()
            watcher.refresh()
            watcher.awaitPendingDirectories()

            val stopped = stopRequested
            console.print("\n" + (if (stopped) PwBundle.message("agents.console.stopped") else exitMessage(code)) + "\n",
                ConsoleViewContentType.SYSTEM_OUTPUT)
            RunResult(code, stopped, HashMap(watcher.before), HashSet(watcher.changed), HashSet(watcher.created))
        } finally {
            Disposer.dispose(watcher)
        }
        options.onFinished?.invoke(result)
    }

    private suspend fun awaitExit(h: KillableColoredProcessHandler, exit: CompletableDeferred<Int>): Int =
        try {
            exit.await()
        } catch (e: CancellationException) {
            if (!h.isProcessTerminated) h.destroyProcess()
            throw e
        }

    private fun closeInput(h: KillableColoredProcessHandler) {
        // Codex reads piped stdin as extra context and Claude waits for it; neither gets any from us.
        try {
            h.processInput.close()
        } catch (_: IOException) {
        }
    }

    private fun exitMessage(code: Int): String =
        if (code == 0) PwBundle.message("agents.console.finished") else PwBundle.message("agents.console.exitCode", code)

    /** Shows the Playwright tool window with the Agents tab selected. Call on the EDT. */
    private fun showConsole(): ConsoleView {
        val view = console
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return view
        toolWindow.show()
        val content = toolWindow.contentManager.contents.firstOrNull { SwingUtilities.isDescendingFrom(view.component, it.component) }
        if (content != null) toolWindow.contentManager.setSelectedContent(content)
        return view
    }

    /** Splits process output into lines and prints them through [AgentBackend.parseLine]. */
    private class AgentOutputListener(
        private val backend: AgentBackend,
        private val console: ConsoleView,
        private val exit: CompletableDeferred<Int>,
    ) : ProcessListener {
        private val stdout = StringBuilder()
        private val stderr = StringBuilder()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when {
                ProcessOutputType.isStdout(outputType) -> feed(stdout, event.text, false)
                ProcessOutputType.isStderr(outputType) -> feed(stderr, event.text, true)
                else -> console.print(event.text, ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            flush()
            exit.complete(event.exitCode)
        }

        @Synchronized
        private fun feed(buffer: StringBuilder, text: String, isStderr: Boolean) {
            buffer.append(text)
            while (true) {
                val nl = buffer.indexOf("\n")
                if (nl < 0) break
                val line = buffer.substring(0, nl).trimEnd('\r')
                buffer.delete(0, nl + 1)
                emit(line, isStderr)
            }
        }

        @Synchronized
        fun flush() {
            if (stdout.isNotEmpty()) emit(stdout.toString(), false).also { stdout.setLength(0) }
            if (stderr.isNotEmpty()) emit(stderr.toString(), true).also { stderr.setLength(0) }
        }

        private fun emit(line: String, isStderr: Boolean) {
            if (isStderr) {
                if (line.isNotBlank()) console.print(line + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                return
            }
            val out = try {
                backend.parseLine(line)
            } catch (_: Exception) {
                AgentOutput(line, false)
            } ?: return
            console.print(out.text + "\n", if (out.isError) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    /** Watches the VFS under the working directory for the duration of one run. */
    private inner class RunWatcher(
        private val config: PwConfig,
        private val trackChanges: Boolean,
        private val openNewFiles: Boolean,
    ) : Disposable {
        val before = ConcurrentHashMap<String, String>()
        val changed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val created: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val opened: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val pendingDirs = CopyOnWriteArrayList<kotlinx.coroutines.Job>()
        private val workingDir: VirtualFile = config.workingDir
        private val ignoredRoots: List<String> =
            (PlaywrightProjectLocator.getInstance(project).cachedProjects(config).orEmpty()
                .map { FileUtil.toSystemIndependentName(it.outputDir.toString()) }).distinct()

        init {
            Disposer.register(this@AgentService, this)
            project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    if (!trackChanges) return
                    for (event in events) {
                        if (event !is VFileContentChangeEvent && event !is VFileDeleteEvent) continue
                        val file = event.file ?: continue
                        if (file.isDirectory || !isWatched(event.path)) continue
                        if (before.containsKey(event.path) || created.contains(event.path)) continue
                        captureText(file)?.let { before.putIfAbsent(event.path, it) }
                    }
                }

                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        val path = event.path
                        if (!isWatched(path)) continue
                        when (event) {
                            is VFileCreateEvent -> {
                                val file = event.file
                                if (event.isDirectory) {
                                    if (file != null) scanCreatedDirectory(file)
                                } else {
                                    onCreated(path, file)
                                }
                            }
                            is VFileContentChangeEvent, is VFileDeleteEvent -> if (event.file?.isDirectory != true) changed += path
                            else -> {}
                        }
                    }
                }
            })
        }

        private fun isWatched(path: String): Boolean {
            if (!FileUtil.isAncestor(workingDir.path, path, true)) return false
            val relative = FileUtil.getRelativePath(workingDir.path, path, '/') ?: return false
            if (relative.split('/').any { it in IGNORED_DIRS }) return false
            return ignoredRoots.none { FileUtil.isAncestor(it, path, false) }
        }

        private fun onCreated(path: String, file: VirtualFile?) {
            created += path
            if (!openNewFiles || file == null || !isOpenable(file) || !opened.add(path)) return
            scope.launch(Dispatchers.EDT) {
                if (file.isValid && !project.isDisposed) {
                    writeIntentReadAction { FileEditorManager.getInstance(project).openFile(file, true) }
                }
            }
        }

        private fun scanCreatedDirectory(dir: VirtualFile) {
            pendingDirs += scope.launch(Dispatchers.IO) {
                val files = readAction {
                    val result = mutableListOf<VirtualFile>()
                    if (dir.isValid) {
                        VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Unit>() {
                            override fun visitFile(file: VirtualFile): Boolean {
                                if (file.isDirectory) return file.name !in IGNORED_DIRS
                                result += file
                                return true
                            }
                        })
                    }
                    result
                }
                files.forEach { if (isWatched(it.path)) onCreated(it.path, it) }
            }
        }

        suspend fun awaitPendingDirectories() {
            pendingDirs.forEach { it.join() }
        }

        /** Text of open documents under the working directory, taken before the run. Call off the EDT. */
        suspend fun snapshotOpenDocuments() {
            val texts = withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(project).openFiles.filter { isWatched(it.path) }
            }.let { files ->
                readAction {
                    files.mapNotNull { f -> FileDocumentManager.getInstance().getCachedDocument(f)?.let { f.path to it.text } }
                }
            }
            texts.forEach { (path, text) -> before.putIfAbsent(path, text) }
        }

        /** Makes the VFS notice files the agent wrote: specs/, the test dirs and snapshotted files' folders. */
        suspend fun refresh() {
            val fs = LocalFileSystem.getInstance()
            val recursive = LinkedHashSet<VirtualFile>()
            workingDir.findChild("specs")?.let { recursive += it }
            PlaywrightProjectLocator.getInstance(project).cachedProjects(config).orEmpty().forEach { p ->
                fs.findFileByNioFile(p.testDir)?.let { recursive += it }
            }
            before.keys.forEach { path -> fs.findFileByPath(path)?.parent?.let { recursive += it } }
            val roots = recursive.filter { it.isValid }
            if (roots.isNotEmpty()) {
                VfsUtil.markDirty(true, true, *roots.toTypedArray())
                RefreshQueue.getInstance().refresh(true, roots)
            }
            if (workingDir.isValid) {
                VfsUtil.markDirty(false, true, workingDir)
                RefreshQueue.getInstance().refresh(false, listOf(workingDir))
            }
        }

        override fun dispose() {
            pendingDirs.forEach { it.cancel() }
        }
    }

    private fun isOpenable(file: VirtualFile): Boolean {
        val name = file.name
        if (name.endsWith(".spec.ts")) return true
        return name.endsWith(".md") && file.parent?.name == "specs"
    }

    private fun captureText(file: VirtualFile): String? {
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { return it.immutableCharSequence.toString() }
        if (file.fileType.isBinary || file.length > MAX_SNAPSHOT_BYTES) return null
        return try {
            LoadTextUtil.getTextByBinaryPresentation(file.contentsToByteArray(), file).toString()
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        private const val OUTPUT_TAIL = 16_000
        private val LOG = logger<AgentService>()
        const val TOOL_WINDOW_ID = "Playwright"
        private const val REFRESH_INTERVAL_MS = 3_000L
        private const val MAX_SNAPSHOT_BYTES = 2L * 1024 * 1024
        private val IGNORED_DIRS = setOf("node_modules", ".git", ".idea", "test-results", "playwright-report", "blob-report")

        fun getInstance(project: Project): AgentService = project.getService(AgentService::class.java)
    }
}
