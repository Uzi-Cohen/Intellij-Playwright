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
 * Ported to Kotlin for Playwright Toolkit from microsoft/playwright-vscode src/settingsView.ts (message handling,
 * the action list and its SVG icons, the settings/models messages).
 */
package dev.uzi.pw.ui.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import dev.uzi.pw.actions.InstallBrowsersAction
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwProjectInfo
import dev.uzi.pw.core.PwRecorderStateListener
import dev.uzi.pw.core.PwSettingsListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.locator.LocatorPicker
import dev.uzi.pw.recorder.RecordAtCursorAction
import dev.uzi.pw.recorder.RecordNewAction
import dev.uzi.pw.recorder.RecorderService
import dev.uzi.pw.reports.ReportingSetup
import dev.uzi.pw.run.UpdateSnapshotsRunPatch
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.JComponent

/** The PLAYWRIGHT view: playwright-vscode's settings view page, driven by the plugin's services. */
class SettingsWebView(private val project: Project, parentDisposable: Disposable) {

    private val settings = PwSettings.getInstance(project)
    private val locator = PlaywrightProjectLocator.getInstance(project)
    private val recorder = RecorderService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val view = PwWebView(parentDisposable, "settingsView", ::onMessage, ::pushAll)

    @Volatile
    private var projects: List<PwProjectInfo> = emptyList()

    val component: JComponent get() = view.component

    init {
        Disposer.register(parentDisposable) { scope.cancel() }
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener { reloadProjects() })
        connection.subscribe(PwTopics.SETTINGS_CHANGED, PwSettingsListener { later { pushSettings(); pushModels(); pushActions() } })
        connection.subscribe(PwTopics.RECORDER_STATE, PwRecorderStateListener { later { pushActions() } })
        reloadProjects()
    }

    // ---- plugin → page (settingsView.ts _updateSettings / _updateActions / _updateModels) ----

    private fun pushAll() {
        pushSettings()
        pushModels()
        pushActions()
    }

    private fun pushSettings() {
        val s = settings.state
        view.post("settings", buildJsonObject {
            putJsonObject("settings") {
                put("reuseBrowser", s.showBrowser && s.reuseBrowser)
                put("showTrace", s.alwaysRecordTrace)
                put("updateSnapshots", s.updateSnapshots ?: UpdateSnapshotsRunPatch.NO_OVERRIDE)
                put("updateSourceMethod", s.updateSourceMethod ?: UpdateSnapshotsRunPatch.NO_OVERRIDE)
            }
        })
    }

    private fun pushModels() {
        val configs = locator.configs()
        val selected = locator.selectedConfig()
        view.post("models", buildJsonObject {
            putJsonArray("configs") {
                for (config in configs) {
                    addJsonObject {
                        put("label", config.displayName)
                        put("configFile", config.configFile.path)
                        put("selected", config == selected)
                        put("enabled", true)
                        putJsonArray("projects") {
                            if (config == selected) {
                                val disabled = settings.disabledProjects(config.configFile.path)
                                for (p in projects) addJsonObject {
                                    put("name", p.name)
                                    put("enabled", p.name !in disabled)
                                }
                            }
                        }
                    }
                }
            }
            put("showModelSelector", configs.size > 1)
        })
    }

    private fun pushActions() {
        val supported = locator.selectedConfig()?.isSupported == true
        val state = recorder.state
        val running = recorder.isRunningTests
        val recording = state == RecorderService.State.RECORDING || state == RecorderService.State.STARTING ||
            state == RecorderService.State.CODEGEN
        val active = recording || state == RecorderService.State.INSPECTING
        view.post("actions", buildJsonObject {
            putJsonArray("actions") {
                action(CMD_PICK, SVG_PICK, "Pick locator", disabled = !supported)
                action(CMD_RECORD_NEW, SVG_RECORD_NEW, "Record new", disabled = !supported || running || recording)
                action(CMD_RECORD_CURSOR, SVG_RECORD_CURSOR, "Record at cursor", disabled = !supported || running || recording)
                action(CMD_STOP, SVG_STOP, "Stop recording", hidden = !active)
                action(CMD_REVEAL, SVG_REVEAL, "Reveal test output")
                action(CMD_CLOSE, SVG_CLOSE, "Close all browsers", disabled = !recorder.isBrowserOpen || running)
                action(CMD_INSTALL, "", "Install browsers", disabled = !supported, location = "rareActions")
                action(CMD_CLEAR_CACHE, "", "Clear cache", disabled = !supported, location = "rareActions")
                action(CMD_REPORTING, "", "Set up reporting", disabled = !supported, location = "rareActions")
            }
        })
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.action(
        command: String,
        svg: String,
        text: String,
        disabled: Boolean = false,
        hidden: Boolean = false,
        location: String? = null,
    ) = addJsonObject {
        put("command", command)
        put("svg", svg)
        put("text", text)
        put("disabled", disabled)
        put("hidden", hidden)
        location?.let { put("location", it) }
    }

    // ---- page → plugin (settingsView.ts onDidReceiveMessage) ----

    private fun onMessage(method: String, params: JsonObject) {
        when (method) {
            "execute" -> execute(params.str("command"))
            "toggle" -> when (params.str("setting")) {
                // playwright-vscode: one setting = headed + reused browser + one worker; it turns trace off.
                "reuseBrowser" -> {
                    val enable = !(settings.state.showBrowser && settings.state.reuseBrowser)
                    settings.setShowBrowser(enable)
                    settings.setReuseBrowser(enable)
                }
                // settingsModel.ts: showTrace and showBrowser turn each other off.
                "showTrace" -> {
                    val enable = !settings.state.alwaysRecordTrace
                    settings.setAlwaysRecordTrace(enable)
                    if (enable) settings.setShowBrowser(false)
                }
            }
            "set" -> when (params.str("setting")) {
                "updateSnapshots" -> params.str("value")?.let { settings.setUpdateSnapshots(it) }
                "updateSourceMethod" -> params.str("value")?.let { settings.setUpdateSourceMethod(it) }
            }
            "setProjectEnabled" -> {
                val configFile = params.str("configFile") ?: return
                val name = params.str("projectName") ?: return
                settings.setProjectEnabled(configFile, name, params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)
            }
            "setAllProjectsEnabled" -> {
                val configFile = params.str("configFile") ?: return
                val enabled = params["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                projects.forEach { settings.setProjectEnabled(configFile, it.name, enabled) }
            }
            "selectModel" -> {
                val configFile = params.str("configFile") ?: return
                locator.configs().firstOrNull { it.configFile.path == configFile }?.let { locator.select(it) }
            }
        }
    }

    private fun execute(command: String?) {
        val config = locator.selectedConfig()?.takeIf { it.isSupported }
        when (command) {
            CMD_PICK -> config?.let { LocatorPicker.getInstance(project).pick(it) }
            CMD_RECORD_NEW -> config?.let { RecordNewAction.start(project, it) }
            CMD_RECORD_CURSOR -> RecordAtCursorAction.start(project, FileEditorManager.getInstance(project).selectedTextEditor)
            CMD_STOP -> recorder.stop()
            CMD_REVEAL -> ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN)?.activate(null)
            CMD_CLOSE -> recorder.closeBrowser()
            CMD_INSTALL -> config?.let { InstallBrowsersAction.run(project, it) }
            CMD_CLEAR_CACHE -> config?.let {
                InstallBrowsersAction.runInConsole(project, it, listOf("clear-cache", "-c", it.configFile.path), "Playwright: clear cache")
            }
            CMD_REPORTING -> config?.let { ReportingSetup.run(project, it) }
        }
    }

    // ---- helpers ----

    private fun reloadProjects() {
        scope.launch {
            val config = locator.selectedConfig()?.takeIf { it.isSupported }
            projects = if (config == null) emptyList() else try {
                locator.projects(config)
            } catch (e: PwException) {
                emptyList()
            }
            withContext(Dispatchers.EDT) { pushAll() }
        }
    }

    private fun later(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ block() }, { project.isDisposed })
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private const val CMD_PICK = "pw.inspect"
        private const val CMD_RECORD_NEW = "pw.recordNew"
        private const val CMD_RECORD_CURSOR = "pw.recordAtCursor"
        private const val CMD_STOP = "pw.stopRecording"
        private const val CMD_REVEAL = "pw.revealTestOutput"
        private const val CMD_CLOSE = "pw.closeBrowsers"
        private const val CMD_INSTALL = "pw.installBrowsers"
        private const val CMD_CLEAR_CACHE = "pw.clearCache"
        private const val CMD_REPORTING = "pw.setupReporting"

        // Icons from playwright-vscode src/settingsView.ts (pickElementAction, recordNewAction, ...).
        const val SVG_PICK = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M18 42h-7.5c-3 0-4.5-1.5-4.5-4.5v-27C6 7.5 7.5 6 10.5 6h27C42 6 42 10.404 42 10.5V18h-3V9H9v30h9v3Zm27-15-9 6 9 9-3 3-9-9-6 9-6-24 24 6Z"/></svg>"""
        private const val SVG_RECORD_NEW = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M18 42h-7.5c-3 0-4.5-1.5-4.5-4.5v-27C6 7.5 7.5 6 10.5 6h27C42 6 42 10.404 42 10.5V18h-3V9H9v30h9v3Zm27-15-9 6 9 9-3 3-9-9-6 9-6-24 24 6Z"/></svg>"""
        private const val SVG_RECORD_CURSOR = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M18 42h-7.5c-3 0-4.5-1.5-4.5-4.5v-27C6 7.5 7.5 6 10.5 6h27C42 6 42 10.404 42 10.5V18h-3V9H9v30h9v3Zm27-15-9 6 9 9-3 3-9-9-6 9-6-24 24 6Z"/></svg>"""
        private const val SVG_REVEAL = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M18 42h-7.5c-3 0-4.5-1.5-4.5-4.5v-27C6 7.5 7.5 6 10.5 6h27C42 6 42 10.404 42 10.5V18h-3V9H9v30h9v3Zm27-15-9 6 9 9-3 3-9-9-6 9-6-24 24 6Z"/></svg>"""
        private const val SVG_CLOSE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M18 42h-7.5c-3 0-4.5-1.5-4.5-4.5v-27C6 7.5 7.5 6 10.5 6h27C42 6 42 10.404 42 10.5V18h-3V9H9v30h9v3Zm27-15-9 6 9 9-3 3-9-9-6 9-6-24 24 6Z"/></svg>"""
        // Own icon (a stop square in the same 48x48 style), for the extra "Stop recording" row.
        private const val SVG_STOP = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M14 14h20v20H14Z"/></svg>"""
    }
}
