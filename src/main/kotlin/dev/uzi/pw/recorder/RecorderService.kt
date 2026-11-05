/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright 2026 Uzi Cohen
 * Ported to Kotlin for Playwright for IntelliJ from microsoft/playwright-vscode src/reusedBrowser.ts
 * (ReusedBrowser: backend lifecycle, recording, event handling) and src/extension.ts (record new flow).
 */
package dev.uzi.pw.recorder

import com.intellij.application.options.CodeStyle
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.InstallBrowsersAction
import dev.uzi.pw.core.DebugControllerClient
import dev.uzi.pw.core.InspectRequestedEvent
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PausedEvent
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwProjectInfo
import dev.uzi.pw.core.PwSettingsListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.core.RecorderMode
import dev.uzi.pw.core.SetModeRequestedEvent
import dev.uzi.pw.core.SetRecorderModeParams
import dev.uzi.pw.core.SourceChangedEvent
import dev.uzi.pw.core.StateChangedEvent
import dev.uzi.pw.locator.LocatorPicker
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Owns the debug controller connection and the recorder state for a project. */
@Service(Service.Level.PROJECT)
class RecorderService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    enum class State { IDLE, STARTING, RECORDING, INSPECTING, CODEGEN }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var pageCount: Int = 0
        private set

    @Volatile
    private var client: DebugControllerClient? = null
    private var clientConfigPath: String? = null
    private val clientMutex = Mutex()

    /** Touched only on the EDT. */
    private var tracker: InsertionTracker? = null

    @Volatile
    private var codegenHandler: KillableColoredProcessHandler? = null

    /** reusedBrowser.ts `_scheduleEdit`: document edits run one after another, in order. */
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Playwright test runs currently using this browser (reusedBrowser.ts `_isRunningTests`). */
    private val runningTests = AtomicInteger(0)

    /** The browser was opened (or adopted) for "reuse browser" test runs. */
    @Volatile
    private var reusedForRuns = false

    val isRunningTests: Boolean get() = runningTests.get() > 0

    init {
        cs.launch(Dispatchers.EDT) {
            for (edit in edits) {
                runCatching { edit() }.onFailure { LOG.warn("Recorder edit failed", it) }
            }
        }
        // reusedBrowser.ts: turning show-browser off closes the browser.
        project.messageBus.connect(cs).subscribe(PwTopics.SETTINGS_CHANGED, PwSettingsListener {
            if (!PwSettings.getInstance(project).isReuseBrowserEffective && reusedForRuns && !isRunningTests && state == State.IDLE) {
                cs.launch { disposeClient() }
            }
        })
    }

    val isBrowserOpen: Boolean get() = client?.isClosed == false

    /** Service coroutine scope, for actions that need to do background work before calling in. */
    val scope: CoroutineScope get() = cs

    // ---------------------------------------------------------------- public API

    /** Record new: create `recorded-N.spec.ts`, open a browser on [url], record into the file. Call on the EDT. */
    fun recordNew(config: PwConfig, projectInfo: PwProjectInfo, url: String?) {
        if (state == State.RECORDING || state == State.CODEGEN || state == State.STARTING) {
            PwNotifications.warn(project, PwBundle.message("recorder.error.busy"))
            return
        }
        val created = NewTestFile.create(project, projectInfo.testDir, url)
        if (created == null) {
            PwNotifications.error(project, PwBundle.message("recorder.error.createFile", projectInfo.testDir.toString()))
            return
        }
        stopActiveRecording()
        val document = created.editor.document
        val tracker = InsertionTracker(document, created.placeholderStart, created.placeholderEnd, indent = 2, firstLinePrefix = "")
        setState(State.STARTING)
        cs.launch {
            val c = try {
                ensureClient(config)
            } catch (e: PwException) {
                LOG.warn("Debug controller failed, using codegen", e)
                withContext(Dispatchers.EDT) { tracker.dispose() }
                setState(State.IDLE)
                withContext(Dispatchers.EDT) { codegen(config, created.file, url) }
                return@launch
            }
            if (!url.isNullOrBlank()) {
                val ok = BootstrapRunner.runRecordedFile(project, config, projectInfo.name, Path.of(created.file.path), c.wsEndpoint)
                if (!ok) PwNotifications.warn(project, PwBundle.message("recorder.warn.bootstrap"))
            }
            startRecording(config, c, tracker, projectInfo)
        }
    }

    /** Record at cursor: insert recorded actions at the caret of [editor]. Call on the EDT. */
    fun recordAtCursor(config: PwConfig, editor: Editor, url: String?) {
        if (state == State.RECORDING || state == State.CODEGEN || state == State.STARTING) {
            PwNotifications.warn(project, PwBundle.message("recorder.error.busy"))
            return
        }
        val tracker = trackerAtCaret(editor)
        setState(State.STARTING)
        cs.launch {
            val c = try {
                ensureClient(config)
            } catch (e: PwException) {
                withContext(Dispatchers.EDT) { tracker.dispose() }
                setState(State.IDLE)
                showError(config, e)
                return@launch
            }
            if (!url.isNullOrBlank() && pageCount == 0) {
                val ok = BootstrapRunner.openUrl(project, config, url, c.wsEndpoint)
                if (!ok) PwNotifications.warn(project, PwBundle.message("recorder.warn.bootstrap"))
            }
            startRecording(config, c, tracker)
        }
    }

    /** Stop recording or inspecting: `setRecorderMode({ mode: 'none' })`. The browser stays open. */
    fun stop() {
        codegenHandler?.destroyProcess()
        val c = client
        stopActiveRecording()
        if (c != null && !c.isClosed) c.resetRecorderModeNoWait()
        setState(State.IDLE)
    }

    /** Closes the browser and the run-server process. */
    fun closeBrowser() {
        // reusedBrowser.ts `closeAllBrowsers`: not while tests run in this browser.
        if (isRunningTests) {
            PwNotifications.warn(project, PwBundle.message("recorder.warn.closeWhileRunning"))
            return
        }
        stop()
        cs.launch { disposeClient() }
    }

    // ---------------------------------------------------------------- reuse browser (reusedBrowser.ts onWillRunTests / onDidRunTests)

    /**
     * Before a "reuse browser" test run: start (or reuse) the debug controller browser, cancel any recording or
     * picking so the run starts from a clean slate (reusedBrowser.ts `_startBackendIfNeeded`), and return the
     * wsEndpoint for `PW_TEST_CONNECT_WS_ENDPOINT`.
     */
    suspend fun prepareForTestRun(config: PwConfig): String {
        if (state == State.RECORDING || state == State.INSPECTING) {
            withContext(Dispatchers.EDT) { stop() }
        }
        val c = ensureClient(config)
        reusedForRuns = true
        return c.wsEndpoint
    }

    fun onTestRunStarted() {
        runningTests.incrementAndGet()
        publish()
    }

    /** reusedBrowser.ts `onDidRunTests`: keep the browser while it has pages, close it otherwise. */
    fun onTestRunFinished() {
        val left = runningTests.updateAndGet { if (it > 0) it - 1 else 0 }
        if (left == 0 && (pageCount == 0 || !PwSettings.getInstance(project).isReuseBrowserEffective)) {
            cs.launch { disposeClient() }
        }
        publish()
    }

    /** Starts (or reuses) the client and switches the recorder to inspecting. Used by [LocatorPicker]. */
    suspend fun startInspecting(config: PwConfig) {
        withContext(Dispatchers.EDT) { stopActiveRecording() }
        val c = ensureClient(config)
        val testId = PlaywrightProjectLocator.getInstance(project).testIdAttribute(config)
        c.setRecorderMode(SetRecorderModeParams(RecorderMode.INSPECTING, testIdAttributeName = testId))
        setState(State.INSPECTING)
    }

    /** The live client, starting it for [config] when needed. */
    suspend fun ensureClient(config: PwConfig): DebugControllerClient = clientMutex.withLock {
        val existing = client
        if (existing != null && !existing.isClosed && clientConfigPath == config.configFile.path) return existing
        existing?.dispose()
        val created = DebugControllerClient.start(project, config, cs, ClientListener())
        client = created
        clientConfigPath = config.configFile.path
        created
    }

    fun currentClient(): DebugControllerClient? = client?.takeIf { !it.isClosed }

    // ---------------------------------------------------------------- recording

    private suspend fun startRecording(config: PwConfig, c: DebugControllerClient, newTracker: InsertionTracker, projectInfo: PwProjectInfo? = null) {
        val testId = PlaywrightProjectLocator.getInstance(project).testIdAttribute(config, projectInfo)
        try {
            c.setRecorderMode(SetRecorderModeParams(RecorderMode.RECORDING, testIdAttributeName = testId))
        } catch (e: PwException) {
            withContext(Dispatchers.EDT) { newTracker.dispose() }
            setState(State.IDLE)
            showError(config, e)
            return
        }
        withContext(Dispatchers.EDT) {
            tracker?.dispose()
            tracker = newTracker
        }
        setState(State.RECORDING)
    }

    private fun trackerAtCaret(editor: Editor): InsertionTracker {
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val unit = psiFile?.let { CodeStyle.getIndentSize(it) } ?: 2
        val indent = InsertionTracker.guessBodyIndentation(document, line, unit)
        val before = document.charsSequence.subSequence(document.getLineStartOffset(line), offset)
        val prefix = if (before.isBlank() && column < indent) " ".repeat(indent - column) else ""
        return InsertionTracker(document, offset, offset, indent, prefix)
    }

    /** Must run on the EDT. */
    private fun stopActiveRecording() {
        tracker?.dispose()
        tracker = null
    }

    private fun onSourceChanged(event: SourceChangedEvent) {
        if (state != State.RECORDING) return
        val actions = event.actions ?: return
        if (actions.isEmpty()) return
        edits.trySend {
            val t = tracker ?: return@trySend
            WriteCommandAction.runWriteCommandAction(project, PwBundle.message("recorder.command.name"), "playwright.record", {
                if (!t.replace(actions)) {
                    LOG.info("Recorded block was removed from the editor; stopping")
                    stop()
                }
            })
        }
    }

    // ---------------------------------------------------------------- codegen fallback

    /** `playwright codegen --target playwright-test -o <file> <url>`; the file is reloaded when the process exits. */
    fun codegen(config: PwConfig, file: VirtualFile, url: String?) {
        val args = mutableListOf("codegen", "--target", "playwright-test", "-o", file.path,
            "--test-id-attribute", cachedTestIdAttribute(config))
        if (!url.isNullOrBlank()) args += url
        val runner = NodeRunner.getInstance(project)
        val cmd = try {
            runner.commandLine(config, args)
        } catch (e: PwException) {
            PwNotifications.error(project, e.message ?: "")
            return
        }
        FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().saveDocument(it) }
        val handler = runner.start(cmd, null, object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                codegenHandler = null
                setState(State.IDLE)
                cs.launch(Dispatchers.EDT) {
                    VfsUtil.markDirtyAndRefresh(false, false, false, file)
                    val document = FileDocumentManager.getInstance().getDocument(file)
                    if (document != null) FileDocumentManager.getInstance().reloadFromDisk(document)
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        })
        codegenHandler = handler
        setState(State.CODEGEN)
        PwNotifications.info(project, PwBundle.message("recorder.info.codegen"))
    }

    // ---------------------------------------------------------------- client events

    private inner class ClientListener : DebugControllerClient.Listener {
        override fun onSourceChanged(event: SourceChangedEvent) = this@RecorderService.onSourceChanged(event)

        override fun onInspectRequested(event: InspectRequestedEvent) {
            LocatorPicker.getInstance(project).onPicked(event.locator, event.ariaSnapshot)
        }

        /** reusedBrowser.ts `setModeRequested` handler. */
        override fun onSetModeRequested(event: SetModeRequestedEvent) {
            when (event.mode) {
                "standby", "none" -> {
                    cs.launch(Dispatchers.EDT) { stopActiveRecording() }
                    setState(State.IDLE)
                }
                "recording" -> if (state != State.RECORDING) {
                    cs.launch(Dispatchers.EDT) {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@launch
                        tracker?.dispose()
                        tracker = trackerAtCaret(editor)
                        setState(State.RECORDING)
                    }
                }
                "inspecting" -> setState(State.INSPECTING)
            }
        }

        /** reusedBrowser.ts `_pageCountChanged`: no pages left means the browser was closed. */
        override fun onStateChanged(event: StateChangedEvent) {
            pageCount = event.pageCount
            // reusedBrowser.ts `_pageCountChanged`: pages come and go while tests run; keep the browser.
            if (event.pageCount == 0 && isRunningTests) {
                publish()
                return
            }
            if (event.pageCount == 0) {
                cs.launch(Dispatchers.EDT) { stopActiveRecording() }
                setState(State.IDLE)
                cs.launch { disposeClient() }
            } else {
                publish()
            }
        }

        override fun onPaused(event: PausedEvent) {
            if (!event.paused) return
            PwNotifications.info(project, PwBundle.message("recorder.info.paused"),
                NotificationAction.createSimpleExpiring(PwBundle.message("recorder.action.resume")) {
                    cs.launch { runCatching { currentClient()?.resume() } }
                })
        }

        override fun onClosed(error: String?) {
            client = null
            pageCount = 0
            reusedForRuns = false
            cs.launch(Dispatchers.EDT) { stopActiveRecording() }
            if (state != State.CODEGEN) setState(State.IDLE)
            if (error != null) PwNotifications.warn(project, PwBundle.message("recorder.warn.closed", error))
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun disposeClient() = clientMutex.withLock {
        client?.dispose()
        client = null
        reusedForRuns = false
        clientConfigPath = null
        pageCount = 0
        publish()
    }

    /** reusedBrowser.ts `showExceptionAsUserError`: offer browser install when Playwright says browsers are missing. */
    private fun showError(config: PwConfig, e: Exception) {
        val message = e.message ?: e.toString()
        if (message.contains("Looks like Playwright Test or Playwright") || message.contains("Executable doesn't exist")) {
            PwNotifications.error(project, PwBundle.message("recorder.error.browsers"),
                NotificationAction.createSimpleExpiring(PwBundle.message("action.installBrowsers.text")) {
                    InstallBrowsersAction.run(project, config)
                })
        } else {
            PwNotifications.error(project, message)
        }
    }

    /** Effective test id attribute without running Playwright (setting, then cached config value, then data-testid). */
    private fun cachedTestIdAttribute(config: PwConfig): String =
        PwSettings.getInstance(project).testIdAttributeOverride
            ?: PlaywrightProjectLocator.getInstance(project).cachedConfigTestIdAttribute(config)
            ?: PlaywrightProjectLocator.DEFAULT_TEST_ID_ATTRIBUTE

    private fun setState(newState: State) {
        state = newState
        publish()
    }

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(PwTopics.RECORDER_STATE).recorderStateChanged()
    }

    override fun dispose() {
        codegenHandler?.destroyProcess()
        client?.dispose()
        client = null
        edits.close()
    }

    companion object {
        private val LOG = logger<RecorderService>()

        fun getInstance(project: Project): RecorderService = project.service()

        /** Projects of [config]; null (with a notification) when listing fails. */
        suspend fun projectsOrNotify(project: Project, config: PwConfig): List<PwProjectInfo>? = try {
            PlaywrightProjectLocator.getInstance(project).projects(config)
        } catch (e: PwException) {
            PwNotifications.error(project, e.message ?: "")
            null
        }
    }
}
