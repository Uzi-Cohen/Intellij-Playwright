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
 * Ported to Kotlin for Playwright for IntelliJ from microsoft/playwright-vscode src/backend.ts
 * (startBackend, BackendClient) and src/reusedBrowser.ts (Backend, _startBackendIfNeeded).
 */
package dev.uzi.pw.core

import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for Playwright's debug controller: starts `playwright run-server --path /<guid> --host 127.0.0.1`,
 * reads `Listening on <ws>` from stdout, and connects to `<ws>?debug-controller` over WebSocket.
 *
 * Message shapes are in [Protocol.kt][DebugControllerProtocol]. Events are delivered on [scope] (a background dispatcher).
 */
class DebugControllerClient private constructor(
    private val scope: CoroutineScope,
    private val handler: KillableColoredProcessHandler,
    val wsEndpoint: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onInspectRequested(event: InspectRequestedEvent) {}
        fun onSetModeRequested(event: SetModeRequestedEvent) {}
        fun onStateChanged(event: StateChangedEvent) {}
        fun onSourceChanged(event: SourceChangedEvent) {}
        fun onPaused(event: PausedEvent) {}

        /** The server process exited or the socket closed. [error] is set when it was not a normal shutdown. */
        fun onClosed(error: String?) {}
    }

    private val lastId = AtomicInteger(0)
    private val callbacks = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private lateinit var webSocket: WebSocket

    val isClosed: Boolean get() = closed.get()

    // ---- commands ----

    suspend fun initialize() {
        send(DebugControllerProtocol.Methods.INITIALIZE, encode(InitializeParams.serializer(), InitializeParams()))
    }

    suspend fun setReportStateChanged(enabled: Boolean) {
        send(DebugControllerProtocol.Methods.SET_REPORT_STATE_CHANGED, encode(SetReportStateChangedParams.serializer(), SetReportStateChangedParams(enabled)))
    }

    suspend fun setRecorderMode(params: SetRecorderModeParams) {
        send(DebugControllerProtocol.Methods.SET_RECORDER_MODE, encode(SetRecorderModeParams.serializer(), params))
    }

    /** Fire-and-forget `setRecorderMode({ mode: 'none' })` (reusedBrowser.ts `resetRecorderModeNoWait`). */
    fun resetRecorderModeNoWait() {
        scope.launch {
            runCatching { setRecorderMode(SetRecorderModeParams(RecorderMode.NONE)) }
        }
    }

    suspend fun highlight(params: HighlightParams) {
        send(DebugControllerProtocol.Methods.HIGHLIGHT, encode(HighlightParams.serializer(), params))
    }

    suspend fun hideHighlight() {
        send(DebugControllerProtocol.Methods.HIDE_HIGHLIGHT, JsonObject(emptyMap()))
    }

    suspend fun resume() {
        send(DebugControllerProtocol.Methods.RESUME, JsonObject(emptyMap()))
    }

    suspend fun kill() {
        send(DebugControllerProtocol.Methods.KILL, JsonObject(emptyMap()))
    }

    /** Sends `kill`, closes the socket and stdin, and destroys the process if it is still alive after 2 s. */
    fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            runCatching { withTimeout(1_000) { sendRaw(DebugControllerProtocol.Methods.KILL, JsonObject(emptyMap()), expectReply = false) } }
            runCatching { webSocket.abort() }
            runCatching { handler.processInput.close() }
            delay(2_000)
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        failPending("Debug controller closed")
    }

    // ---- transport ----

    /** backend.ts `send`: `{ id, guid: 'DebugController', method, params, metadata: { timeout: 0 } }`. */
    suspend fun send(method: String, params: JsonObject): JsonElement? = sendRaw(method, params, expectReply = true)

    private suspend fun sendRaw(method: String, params: JsonObject, expectReply: Boolean): JsonElement? {
        if (closed.get() && method != DebugControllerProtocol.Methods.KILL) throw PwException("Debug controller is closed")
        val id = lastId.incrementAndGet()
        val request = DcRequest(id = id, method = method, params = params)
        val text = DebugControllerProtocol.json.encodeToString(DcRequest.serializer(), request)
        val deferred = CompletableDeferred<JsonElement?>()
        if (expectReply) callbacks[id] = deferred
        LOG.debug("--> $text")
        sendMutex.withLock {
            webSocket.sendText(text, true).await()
        }
        return if (expectReply) deferred.await() else null
    }

    private fun onMessage(text: String) {
        LOG.debug("<-- $text")
        val message = try {
            DebugControllerProtocol.json.decodeFromString(DcIncoming.serializer(), text)
        } catch (e: Exception) {
            LOG.warn("Cannot parse debug controller message: $text", e)
            return
        }
        val id = message.id
        if (id == null || id == 0) {
            dispatchEvent(message.method ?: return, message.params ?: JsonObject(emptyMap()))
            return
        }
        val callback = callbacks.remove(id) ?: return
        val error = message.error
        if (error != null) {
            val msg = error.error?.message ?: error.value?.toString() ?: "Unknown debug controller error"
            callback.completeExceptionally(PwException(msg))
        } else {
            callback.complete(message.result)
        }
    }

    private fun dispatchEvent(method: String, params: JsonObject) {
        val json = DebugControllerProtocol.json
        scope.launch(Dispatchers.Default) {
            try {
                when (method) {
                    DebugControllerProtocol.Events.INSPECT_REQUESTED ->
                        listener.onInspectRequested(json.decodeFromJsonElement(InspectRequestedEvent.serializer(), params))
                    DebugControllerProtocol.Events.SET_MODE_REQUESTED ->
                        listener.onSetModeRequested(json.decodeFromJsonElement(SetModeRequestedEvent.serializer(), params))
                    DebugControllerProtocol.Events.STATE_CHANGED ->
                        listener.onStateChanged(json.decodeFromJsonElement(StateChangedEvent.serializer(), params))
                    DebugControllerProtocol.Events.SOURCE_CHANGED ->
                        listener.onSourceChanged(json.decodeFromJsonElement(SourceChangedEvent.serializer(), params))
                    DebugControllerProtocol.Events.PAUSED ->
                        listener.onPaused(json.decodeFromJsonElement(PausedEvent.serializer(), params))
                    else -> LOG.debug("Ignoring debug controller event $method")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to handle debug controller event $method", e)
            }
        }
    }

    private fun onTransportClosed(error: String?) {
        val wasOpen = closed.compareAndSet(false, true)
        failPending(error ?: "Debug controller connection closed")
        if (wasOpen) {
            if (!handler.isProcessTerminated) handler.destroyProcess()
            scope.launch(Dispatchers.Default) { listener.onClosed(error) }
        }
    }

    private fun failPending(reason: String) {
        val pending = callbacks.values.toList()
        callbacks.clear()
        pending.forEach { it.completeExceptionally(PwException(reason)) }
    }

    private inner class SocketListener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                onMessage(text)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            onTransportClosed(null)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onTransportClosed(error.message ?: error.toString())
        }
    }

    companion object {
        private val LOG = logger<DebugControllerClient>()
        private const val START_TIMEOUT_MS = 30_000L

        private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
            DebugControllerProtocol.json.encodeToJsonElement(serializer, value).jsonObject

        /**
         * Starts run-server for [config], connects, sends `initialize` and `setReportStateChanged({ enabled: true })`
         * (reusedBrowser.ts `Backend.initialize`). Throws [PwException] on failure.
         */
        suspend fun start(project: Project, config: PwConfig, scope: CoroutineScope, listener: Listener): DebugControllerClient {
            val args = listOf("run-server", "--path=/${UUID.randomUUID()}", "--host", "127.0.0.1")
            val cmd = NodeRunner.getInstance(project).commandLine(config, args, DebugControllerProtocol.SERVER_ENV)
            val endpoint = CompletableDeferred<String>()
            val stderr = StringBuffer()
            var client: DebugControllerClient? = null

            val processListener = object : ProcessListener {
                private val stdout = StringBuilder()

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType == ProcessOutputTypes.STDERR) {
                        stderr.append(event.text)
                        LOG.info("[run-server err] ${event.text.trimEnd()}")
                        return
                    }
                    if (outputType != ProcessOutputTypes.STDOUT || endpoint.isCompleted) return
                    stdout.append(event.text)
                    val line = stdout.lines().firstOrNull { it.contains(DebugControllerProtocol.LISTENING_PREFIX) } ?: return
                    endpoint.complete(line.substringAfter(DebugControllerProtocol.LISTENING_PREFIX).trim())
                }

                override fun processTerminated(event: ProcessEvent) {
                    if (!endpoint.isCompleted) {
                        endpoint.completeExceptionally(PwException("run-server exited (${event.exitCode}): ${stderr.toString().take(1000)}"))
                    }
                    client?.onTransportClosed(if (event.exitCode == 0) null else "run-server exited with ${event.exitCode}")
                }
            }

            val handler = NodeRunner.getInstance(project).start(cmd, console = null, listener = processListener)
            val wsEndpoint = try {
                withTimeout(START_TIMEOUT_MS) { endpoint.await() }
            } catch (e: Exception) {
                handler.destroyProcess()
                throw if (e is PwException) e else PwException("run-server did not start: ${e.message}")
            }

            val created = DebugControllerClient(scope, handler, wsEndpoint, listener)
            client = created
            try {
                val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
                created.webSocket = http.newWebSocketBuilder()
                    .buildAsync(URI.create(wsEndpoint + DebugControllerProtocol.WS_QUERY), created.SocketListener())
                    .await()
                created.initialize()
                created.setReportStateChanged(true)
            } catch (e: Exception) {
                handler.destroyProcess()
                throw if (e is PwException) e else PwException("Cannot connect to the debug controller: ${e.message}")
            }
            return created
        }
    }
}
