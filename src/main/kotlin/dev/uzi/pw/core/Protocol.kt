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
 * Debug controller message shapes for Playwright for IntelliJ.
 *
 * Shapes:  microsoft/playwright packages/protocol/src/protocol.yml at v1.56.0, interface `DebugController`
 *          (identical on main @ 7135071, packages/protocol/spec/playwright.yml).
 * Framing: microsoft/playwright-vscode src/backend.ts (`BackendClient.send`, `_connect`) and
 *          src/reusedBrowser.ts (`Backend`), @ 27fa358.
 *
 * This is the ONLY file that defines debug controller message shapes.
 */
package dev.uzi.pw.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object DebugControllerProtocol {
    /** Playwright version the shapes below were copied from. */
    const val PINNED_PLAYWRIGHT = "1.56.0"

    /** Every command targets this guid (backend.ts `send`). */
    const val GUID = "DebugController"

    /** Appended to the run-server wsEndpoint (reusedBrowser.ts `Backend.rewriteWsEndpoint`). */
    const val WS_QUERY = "?debug-controller"

    /** Prefix of the line run-server and show-trace print with their endpoint (cli/driver.ts, traceViewer.ts). */
    const val LISTENING_PREFIX = "Listening on "

    /** Environment for run-server (reusedBrowser.ts `_startBackendIfNeeded`). */
    val SERVER_ENV = mapOf("PW_CODEGEN_NO_INSPECTOR" to "1", "PW_EXTENSION_MODE" to "1")

    /** `initialize` params sent by playwright-vscode (reusedBrowser.ts `Backend.initialize`). */
    const val CODEGEN_ID = "playwright-test"
    const val SDK_LANGUAGE = "javascript"

    object Methods {
        const val INITIALIZE = "initialize"
        const val SET_REPORT_STATE_CHANGED = "setReportStateChanged"
        const val SET_RECORDER_MODE = "setRecorderMode"
        const val HIGHLIGHT = "highlight"
        const val HIDE_HIGHLIGHT = "hideHighlight"
        const val RESUME = "resume"
        const val KILL = "kill"
    }

    object Events {
        const val INSPECT_REQUESTED = "inspectRequested"
        const val SET_MODE_REQUESTED = "setModeRequested"
        const val STATE_CHANGED = "stateChanged"
        const val SOURCE_CHANGED = "sourceChanged"
        const val PAUSED = "paused"
    }

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

// ---- setRecorderMode.mode: enum literals inspecting | recording | none (no `standby` in 1.56) ----

@Serializable
enum class RecorderMode {
    @SerialName("inspecting") INSPECTING,
    @SerialName("recording") RECORDING,
    @SerialName("none") NONE,
}

// ---- Outgoing envelope: { id, guid: 'DebugController', method, params, metadata: { timeout: 0 } } ----

@Serializable
data class DcRequest(
    val id: Int,
    val guid: String = DebugControllerProtocol.GUID,
    val method: String,
    val params: JsonObject,
    val metadata: DcMetadata = DcMetadata(),
)

@Serializable
data class DcMetadata(val timeout: Int = 0)

// ---- Command params ----

/** initialize: { codegenId: string, sdkLanguage: SDKLanguage } */
@Serializable
data class InitializeParams(
    val codegenId: String = DebugControllerProtocol.CODEGEN_ID,
    val sdkLanguage: String = DebugControllerProtocol.SDK_LANGUAGE,
)

/** setReportStateChanged: { enabled: boolean } */
@Serializable
data class SetReportStateChangedParams(val enabled: Boolean)

/** setRecorderMode: { mode, testIdAttributeName?: string, generateAutoExpect?: boolean } */
@Serializable
data class SetRecorderModeParams(
    val mode: RecorderMode,
    val testIdAttributeName: String? = null,
    val generateAutoExpect: Boolean? = null,
)

/** highlight: { selector?: string, ariaTemplate?: string } */
@Serializable
data class HighlightParams(val selector: String? = null, val ariaTemplate: String? = null)

// hideHighlight, resume, kill: no parameters (sent as {}).

// ---- Incoming: response { id, result } | { id, error: { error: { message, stack } } | { value } }; event { method, params } ----

@Serializable
data class DcIncoming(
    val id: Int? = null,
    val guid: String? = null,
    val method: String? = null,
    val params: JsonObject? = null,
    val result: JsonElement? = null,
    val error: DcErrorPayload? = null,
)

@Serializable
data class DcErrorPayload(val error: DcSerializedError? = null, val value: JsonElement? = null)

@Serializable
data class DcSerializedError(val message: String? = null, val stack: String? = null, val name: String? = null)

// ---- Events ----

/** inspectRequested: { selector: string, locator: string, ariaSnapshot: string } */
@Serializable
data class InspectRequestedEvent(val selector: String, val locator: String, val ariaSnapshot: String = "")

/** setModeRequested: { mode: string } (may be "standby") */
@Serializable
data class SetModeRequestedEvent(val mode: String)

/** stateChanged: { pageCount: int } */
@Serializable
data class StateChangedEvent(val pageCount: Int)

/** sourceChanged: { text: string, header?: string, footer?: string, actions?: string[] } */
@Serializable
data class SourceChangedEvent(
    val text: String,
    val header: String? = null,
    val footer: String? = null,
    val actions: List<String>? = null,
)

/** paused: { paused: boolean } */
@Serializable
data class PausedEvent(val paused: Boolean)
