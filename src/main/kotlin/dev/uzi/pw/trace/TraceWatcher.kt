/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.trace

import dev.uzi.pw.run.PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwTopics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Keeps [TraceIndex] in sync with the `trace.zip` files under every Playwright project's `outputDir`.
 *
 * - One scan per outputDir when started and whenever the configs change (plain NIO walk on [Dispatchers.IO]).
 * - `LocalFileSystem.addRootToWatch` for each outputDir, plus a recursive VFS refresh so later changes produce events.
 * - A VFS listener: a created or changed `trace.zip` under an outputDir is added at once; any other change under an
 *   outputDir (deleted folders, a freshly created `test-results`) triggers a debounced rescan.
 * - A rescan when a Playwright run configuration finishes, in case the file watcher is unavailable.
 *
 * Publishes [PwTopics.TRACES_CHANGED] whenever the index content changes.
 */
@Service(Service.Level.PROJECT)
class TraceWatcher(private val project: Project, private val cs: CoroutineScope) : Disposable {

    /** An output directory and the names of the Playwright projects that write into it. */
    private data class Root(val outputDir: Path, val projectNames: List<String>) {
        val vfsPath: String = FileUtil.toSystemIndependentName(outputDir.toString())
    }

    private val started = AtomicBoolean(false)
    private val rescanRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var roots: List<Root> = emptyList()
    private var watchRequests: Set<LocalFileSystem.WatchRequest> = emptySet()
    private val watchLock = Any()
    private var reloadJob: Job? = null

    /** Starts watching; safe to call more than once. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return

        val connection = project.messageBus.connect(cs)
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener { reloadRoots() })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onVfsEvents(events)
        })
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                val profile = env.runProfile
                if (profile is RunConfiguration && profile.type.id == PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID) {
                    refreshVfs(roots)
                    rescan()
                }
            }
        })

        cs.launch {
            for (ignored in rescanRequests) {
                delay(RESCAN_DEBOUNCE_MS)
                try {
                    scan(roots)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Trace scan failed", e)
                }
            }
        }
        reloadRoots()
    }

    /** Schedules a full rescan of all output directories. */
    fun rescan() {
        rescanRequests.trySend(Unit)
    }

    private fun reloadRoots() {
        synchronized(watchLock) {
            reloadJob?.cancel()
            reloadJob = cs.launch {
                val newRoots = collectRoots()
                roots = newRoots
                updateWatchRoots(newRoots)
                refreshVfs(newRoots)
                scan(newRoots)
            }
        }
    }

    private suspend fun collectRoots(): List<Root> {
        val locator = PlaywrightProjectLocator.getInstance(project)
        val byDir = LinkedHashMap<Path, MutableList<String>>()
        for (config in locator.configs().filter { it.isSupported }) {
            val projects = locator.cachedProjects(config) ?: try {
                locator.projects(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PwException) {
                LOG.info("Cannot list projects of ${config.configFile.path}: ${e.message}")
                null
            } catch (e: Exception) {
                LOG.warn("Cannot list projects of ${config.configFile.path}", e)
                null
            }
            if (projects.isNullOrEmpty()) {
                // Playwright's default outputDir.
                val dir = Path.of(config.workingDir.path, "test-results").toAbsolutePath().normalize()
                byDir.getOrPut(dir) { mutableListOf() }
            } else {
                for (p in projects) {
                    val dir = p.outputDir.toAbsolutePath().normalize()
                    byDir.getOrPut(dir) { mutableListOf() }.add(p.name)
                }
            }
        }
        return byDir.map { (dir, names) -> Root(dir, names.distinct()) }
    }

    private fun updateWatchRoots(newRoots: List<Root>) {
        synchronized(watchLock) {
            watchRequests = LocalFileSystem.getInstance().replaceWatchedRoots(
                watchRequests,
                newRoots.map { it.vfsPath },
                emptyList(),
            )
        }
    }

    /** Loads the output dirs into the VFS (async, recursive) so the file watcher reports changes inside them. */
    private fun refreshVfs(currentRoots: List<Root>) {
        cs.launch(Dispatchers.IO) {
            val files = currentRoots.filter { it.outputDir.isDirectory() }
                .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it.outputDir) }
            if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(true, true, true, *files.toTypedArray())
        }
    }

    private suspend fun scan(currentRoots: List<Root>) {
        val found = withContext(Dispatchers.IO) {
            currentRoots.flatMap { root -> scanRoot(root) }
        }
        if (TraceIndex.getInstance(project).replaceAll(found)) publish()
    }

    private fun scanRoot(root: Root): List<TraceEntry> {
        if (!root.outputDir.isDirectory()) return emptyList()
        return try {
            Files.walk(root.outputDir, MAX_SCAN_DEPTH).use { stream ->
                stream.asSequence()
                    .filter { it.fileName?.toString() == TRACE_FILE_NAME && it.isRegularFile() }
                    .mapNotNull { entryFor(it, root) }
                    .toList()
            }
        } catch (e: Exception) {
            LOG.info("Cannot scan ${root.outputDir}: ${e.message}")
            emptyList()
        }
    }

    private fun onVfsEvents(events: List<VFileEvent>) {
        val currentRoots = roots
        if (currentRoots.isEmpty()) return
        var needRescan = false
        val added = mutableListOf<TraceEntry>()
        for (event in events) {
            val path = event.path
            val root = currentRoots.firstOrNull { path.startsWith(it.vfsPath + "/") }
            if (root == null) {
                // The output dir itself (or one of its parents) was created, deleted or moved.
                if (currentRoots.any { it.vfsPath == path || it.vfsPath.startsWith("$path/") }) needRescan = true
                continue
            }
            val isTraceZip = path.endsWith("/$TRACE_FILE_NAME")
            if (isTraceZip && (event is VFileCreateEvent || event is VFileContentChangeEvent)) {
                val nio = Path.of(FileUtil.toSystemDependentName(path))
                entryFor(nio, root)?.let { added += it } ?: run { needRescan = true }
            } else {
                needRescan = true
            }
        }
        if (added.isNotEmpty()) {
            val index = TraceIndex.getInstance(project)
            var changed = false
            for (entry in added) changed = index.add(entry) || changed
            if (changed) publish()
        }
        if (needRescan) rescan()
    }

    private fun publish() {
        project.messageBus.syncPublisher(PwTopics.TRACES_CHANGED).tracesChanged()
    }

    override fun dispose() {
        synchronized(watchLock) {
            LocalFileSystem.getInstance().removeWatchedRoots(watchRequests)
            watchRequests = emptySet()
        }
    }

    companion object {
        private val LOG = logger<TraceWatcher>()

        const val TRACE_FILE_NAME: String = "trace.zip"
        private const val MAX_SCAN_DEPTH = 6
        private const val RESCAN_DEBOUNCE_MS = 500L

        private val RETRY_SUFFIX = Regex("""-retry(\d+)$""")
        private val REPEAT_SUFFIX = Regex("""-repeat\d+$""")

        // Same character class as Playwright's sanitizeForFilePath (packages/playwright-core/src/utils/fileUtils.ts).
        private val UNSAFE_CHARS = Regex("[\\x00-\\x2C\\x2E-\\x2F\\x3A-\\x40\\x5B-\\x60\\x7B-\\x7F]+")

        @JvmStatic
        fun getInstance(project: Project): TraceWatcher = project.service()

        private fun sanitizeForFilePath(s: String): String = s.replace(UNSAFE_CHARS, "-")

        /**
         * Playwright names a test's output folder `<file>-<title>[-<projectId>][-retryN][-repeatN]`
         * (packages/playwright/src/worker/testInfo.ts). This strips the suffixes back off.
         */
        private fun entryFor(zip: Path, root: Root): TraceEntry? {
            val absolute = zip.toAbsolutePath().normalize()
            val mtime = try {
                Files.getLastModifiedTime(absolute).toMillis()
            } catch (e: Exception) {
                return null
            }
            val folder = absolute.parent
            var name = if (folder == null || folder == root.outputDir) {
                absolute.fileName.toString()
            } else {
                folder.fileName.toString()
            }
            name = name.replace(REPEAT_SUFFIX, "")
            var retry = 0
            RETRY_SUFFIX.find(name)?.let { m ->
                retry = m.groupValues[1].toIntOrNull() ?: 0
                name = name.substring(0, m.range.first)
            }
            val projectName = root.projectNames
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
                .firstOrNull { name.endsWith("-" + sanitizeForFilePath(it)) }
            if (projectName != null) {
                name = name.substring(0, name.length - sanitizeForFilePath(projectName).length - 1)
            }
            if (name.isEmpty()) name = folder?.fileName?.toString() ?: TRACE_FILE_NAME
            return TraceEntry(absolute, mtime, projectName, name, retry)
        }
    }
}

/** Starts [TraceWatcher] when a project opens. */
class TraceWatcherStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        TraceWatcher.getInstance(project).ensureStarted()
    }
}
