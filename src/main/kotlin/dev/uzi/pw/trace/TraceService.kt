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
 * Ported to Kotlin for Playwright for IntelliJ from microsoft/playwright-vscode src/spawnTraceViewer.ts.
 */
package dev.uzi.pw.trace

import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * One long-lived trace viewer server per project:
 * `node <cli.js> show-trace --host 127.0.0.1 --port 0 --stdin`.
 *
 * - `--host`/`--port` make Playwright serve the viewer over HTTP instead of launching its own browser window
 *   (packages/playwright-core/src/cli/program.ts). `PWTEST_UNDER_TEST=1` stops it from also opening the system
 *   browser (`openTraceInBrowser` in server/trace/viewer/traceViewer.ts).
 * - The URL is read from the `Listening on <url>` line on stdout and published in [serverUrl].
 * - [open] writes `<absolute path>\n` to stdin; Playwright's `StdinServer` pushes it to the open viewer page.
 * - The server only serves files under its cwd (`traceFileRoots(undefined)` is `[process.cwd()]`), so the cwd is the
 *   config's working directory, widened to a common ancestor (with a restart) when a trace lies outside it.
 * - Restarted when the selected config changes.
 */
@Service(Service.Level.PROJECT)
class TraceService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    private class Running(
        val handler: KillableColoredProcessHandler,
        val config: PwConfig,
        val cwd: Path,
        val url: CompletableDeferred<String>,
    )

    private val mutex = Mutex()

    @Volatile
    private var running: Running? = null

    @Volatile
    private var currentFile: Path? = null

    private val urlState = MutableStateFlow<String?>(null)

    /** URL of the running viewer, null while it is not running. */
    val serverUrl: StateFlow<String?> = urlState.asStateFlow()

    init {
        project.messageBus.connect(cs).subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener {
            val current = running ?: return@PwConfigsListener
            val selected = PlaywrightProjectLocator.getInstance(project).selectedConfig()
            if (selected == null || !sameConfig(selected, current.config)) {
                cs.launch {
                    val file = currentFile
                    close()
                    if (file != null && selected != null && selected.isSupported) open(file)
                }
            }
        })
    }

    fun currentFile(): Path? = currentFile

    /** Calls [onUrl] on the EDT with the current URL and every later change, until [parentDisposable] is disposed. */
    fun watchUrl(parentDisposable: Disposable, onUrl: (String?) -> Unit) {
        val job = cs.launch(Dispatchers.EDT) {
            serverUrl.collect { onUrl(it) }
        }
        Disposer.register(parentDisposable) { job.cancel() }
    }

    /** Starts the viewer without loading a trace (vscode `willRunTests`). */
    fun startInBackground() {
        cs.launch { startIfNeeded(null) }
    }

    /** Loads [zip] into the viewer, starting it first when needed. Never blocks the caller. */
    fun open(zip: Path) {
        val absolute = zip.toAbsolutePath().normalize()
        currentFile = absolute
        cs.launch {
            try {
                val run = startIfNeeded(absolute) ?: return@launch
                val url = withTimeoutOrNull(START_TIMEOUT_MS) { run.url.await() }
                if (url == null) {
                    PwNotifications.error(project, PwBundle.message("trace.error.noUrl"))
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    // StdinServer throws (and the process dies) on a path that does not exist.
                    if (!Files.isRegularFile(absolute)) {
                        PwNotifications.warn(project, PwBundle.message("trace.error.missing", absolute.toString()))
                        return@withContext
                    }
                    val stdin = run.handler.processInput
                    stdin.write((absolute.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                    stdin.flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Cannot open trace $absolute", e)
                PwNotifications.error(project, PwBundle.message("trace.error.open", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Stops the viewer: closes stdin (Playwright exits on stdin close), then destroys the process. */
    fun close() {
        val run = running ?: return
        running = null
        currentFile = null
        urlState.value = null
        stop(run)
    }

    private fun stop(run: Running) {
        try {
            run.handler.processInput.close()
        } catch (e: Exception) {
            LOG.debug(e)
        }
        run.handler.destroyProcess()
    }

    /** Returns the running viewer, starting (or restarting) it when needed; null when it cannot run. */
    private suspend fun startIfNeeded(file: Path?): Running? = mutex.withLock {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()
        if (config == null || !config.isSupported) {
            PwNotifications.warn(project, PwBundle.message("trace.error.noConfig"))
            return@withLock null
        }
        val workingDir = Path.of(config.workingDir.path).toAbsolutePath().normalize()
        val current = running
        if (current != null && !current.handler.isProcessTerminated && sameConfig(current.config, config) &&
            (file == null || file.startsWith(current.cwd))
        ) {
            return@withLock current
        }
        if (current != null) {
            running = null
            urlState.value = null
            stop(current)
        }
        val cwd = if (file == null || file.startsWith(workingDir)) workingDir else commonAncestor(workingDir, file)
        val deferred = CompletableDeferred<String>()
        val cmd = try {
            NodeRunner.getInstance(project).commandLine(
                config,
                listOf("show-trace", "--host", "127.0.0.1", "--port", "0", "--stdin"),
                mapOf("PWTEST_UNDER_TEST" to "1"),
            ).withWorkingDirectory(cwd)
        } catch (e: PwException) {
            PwNotifications.error(project, e.message ?: "")
            return@withLock null
        }
        var started: Running? = null
        val listener = object : ProcessListener {
            private val buffer = StringBuilder()

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                if (outputType == ProcessOutputTypes.SYSTEM) return
                if (outputType == ProcessOutputTypes.STDERR) {
                    LOG.info("trace viewer: ${text.trimEnd()}")
                    return
                }
                if (deferred.isCompleted) return
                synchronized(buffer) {
                    buffer.append(text)
                    LISTENING.find(buffer)?.let { m ->
                        deferred.complete(m.groupValues[1].trim())
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                deferred.completeExceptionally(PwException(PwBundle.message("trace.error.exited", event.exitCode)))
                if (running === started && started != null) {
                    running = null
                    currentFile = null
                    urlState.value = null
                }
            }
        }
        val handler = try {
            withContext(Dispatchers.IO) { NodeRunner.getInstance(project).start(cmd, null, listener) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Cannot start trace viewer", e)
            PwNotifications.error(project, PwBundle.message("trace.error.start", e.message ?: e.javaClass.simpleName))
            return@withLock null
        }
        val run = Running(handler, config, cwd, deferred)
        started = run
        running = run
        cs.launch {
            val url = try {
                withTimeoutOrNull(START_TIMEOUT_MS) { deferred.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (url != null && running === run) urlState.value = url
        }
        run
    }

    override fun dispose() {
        close()
    }

    companion object {
        private val LOG = logger<TraceService>()
        private val LISTENING = Regex("""Listening on (\S+)\r?\n""")
        private const val START_TIMEOUT_MS = 30_000L

        @JvmStatic
        fun getInstance(project: Project): TraceService = project.service()

        private fun sameConfig(a: PwConfig, b: PwConfig): Boolean =
            a.configFile.path == b.configFile.path && a.cliJs == b.cliJs && a.workingDir.path == b.workingDir.path

        private fun commonAncestor(a: Path, b: Path): Path {
            var candidate: Path? = a
            while (candidate != null && !b.startsWith(candidate)) candidate = candidate.parent
            return candidate ?: (b.root ?: b.parent ?: a)
        }
    }
}
