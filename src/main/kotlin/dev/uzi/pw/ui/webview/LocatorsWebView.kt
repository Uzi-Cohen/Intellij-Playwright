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
 * Ported to Kotlin for Playwright Toolkit from microsoft/playwright-vscode src/locatorsView.ts (messages and the
 * pick actions next to each field). Added: insert-at-caret buttons for the locator and the aria assertion.
 */
package dev.uzi.pw.ui.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwRecorderStateListener
import dev.uzi.pw.core.PwSettingsListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.locator.LocatorPicker
import dev.uzi.pw.settings.PwSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.swing.JComponent

/** The LOCATORS view: playwright-vscode's locators view page, driven by [LocatorPicker]. */
class LocatorsWebView(private val project: Project, parentDisposable: Disposable) {

    private val picker = LocatorPicker.getInstance(project)
    private val settings = PwSettings.getInstance(project)
    private val view = PwWebView(parentDisposable, "locatorsView", ::onMessage, ::pushAll)

    val component: JComponent get() = view.component

    init {
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(PwTopics.RECORDER_STATE, PwRecorderStateListener { later { pushValues() } })
        connection.subscribe(PwTopics.SETTINGS_CHANGED, PwSettingsListener { later { pushSettings() } })
    }

    private fun pushAll() {
        pushActions()
        pushValues()
        pushSettings()
    }

    /** locatorsView.ts `_updateActions`: the pick action next to both fields, plus the plugin's insert buttons. */
    private fun pushActions() {
        view.post("actions", buildJsonObject {
            putJsonArray("actions") {
                addJsonObject {
                    put("command", CMD_PICK)
                    put("svg", SettingsWebView.SVG_PICK)
                    put("text", "Pick locator")
                }
                addJsonObject {
                    put("command", CMD_PICK)
                    put("svg", SettingsWebView.SVG_PICK)
                    put("text", "Pick locator")
                    put("location", "actions-2")
                }
                addJsonObject {
                    put("command", CMD_INSERT_LOCATOR)
                    put("svg", SVG_INSERT)
                    put("text", "Insert locator at caret")
                    put("location", "locatorExtra")
                }
                addJsonObject {
                    put("command", CMD_INSERT_ARIA)
                    put("svg", SVG_INSERT)
                    put("text", "Insert toMatchAriaSnapshot assertion at caret")
                    put("location", "ariaExtra")
                }
            }
        })
    }

    /** locatorsView.ts `_updateValues`. */
    private fun pushValues() {
        view.post("update", buildJsonObject {
            putJsonObject("locator") {
                put("locator", picker.locator)
                picker.locatorError?.let { put("error", it) }
            }
            putJsonObject("ariaSnapshot") {
                put("yaml", picker.ariaSnapshot)
                picker.ariaError?.let { put("error", it) }
            }
            put("hideAria", false)
        })
    }

    private fun pushSettings() {
        view.post("settings", buildJsonObject {
            putJsonObject("settings") { put("pickLocatorCopyToClipboard", settings.state.pickLocatorCopyToClipboard) }
        })
    }

    private fun onMessage(method: String, params: JsonObject) {
        when (method) {
            "locatorChanged" -> picker.highlightDebounced(params.str("locator").orEmpty())
            "ariaSnapshotChanged" -> picker.highlightAriaDebounced(params.str("ariaSnapshot").orEmpty())
            "toggle" -> if (params.str("setting") == "pickLocatorCopyToClipboard") {
                settings.setPickLocatorCopyToClipboard(!settings.state.pickLocatorCopyToClipboard)
            }
            "execute" -> when (params.str("command")) {
                CMD_PICK -> PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported }?.let { picker.pick(it) }
                CMD_INSERT_LOCATOR -> picker.insertAtCaret(picker.locator)
                CMD_INSERT_ARIA -> picker.insertAriaAssertion(picker.locator, picker.ariaSnapshot)
            }
        }
    }

    private fun later(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ block() }, { project.isDisposed })
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private const val CMD_PICK = "pw.inspect"
        private const val CMD_INSERT_LOCATOR = "pw.insertLocator"
        private const val CMD_INSERT_ARIA = "pw.insertAria"

        // Own icon in the same 48x48 style: a caret with an arrow into it ("insert at caret").
        private const val SVG_INSERT = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M22.5 6h3v36h-3Zm-14 16.5h9.4l-3.95-3.95 2.1-2.1L23.6 24l-7.55 7.55-2.1-2.1 3.95-3.95H8.5Zm19 0h12.5v3H27.5Z"/></svg>"""
    }
}
