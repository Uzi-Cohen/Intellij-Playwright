/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.layout.selected
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator

/** Settings | Tools | Playwright. */
class PwConfigurable(private val project: Project) : BoundConfigurable(PwBundle.message("settings.title")) {

    private val settings = PwSettings.getInstance(project)
    private lateinit var apiKeyField: JBPasswordField
    private var apiKeyLoaded: String? = null

    override fun createPanel(): DialogPanel {
        val state = settings.state
        apiKeyLoaded = settings.apiKey()
        return panel {
            group(PwBundle.message("settings.group.node")) {
                row(PwBundle.message("settings.node.override")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile(), project)
                        .align(AlignX.FILL)
                        .bindText({ state.nodeInterpreterPath ?: "" }, { state.nodeInterpreterPath = it.ifBlank { null } })
                        .comment(PwBundle.message("settings.node.override.comment"))
                }
                row(PwBundle.message("settings.versions")) {
                    val text = PlaywrightProjectLocator.getInstance(project).configs().joinToString("<br>") {
                        "${it.displayName}: ${it.version?.raw ?: PwBundle.message("locator.version.notFound")}"
                    }.ifEmpty { PwBundle.message("settings.versions.none") }
                    comment(text)
                }
            }
            group(PwBundle.message("settings.group.recorder")) {
                row(PwBundle.message("settings.record.url")) {
                    textField()
                        .align(AlignX.FILL)
                        .bindText({ state.defaultRecordUrl ?: "" }, { state.defaultRecordUrl = it.trim().ifBlank { null } })
                }
                row(PwBundle.message("settings.testIdAttribute")) {
                    val locator = PlaywrightProjectLocator.getInstance(project)
                    val detected = locator.selectedConfig()?.let { locator.cachedConfigTestIdAttribute(it) }
                    textField()
                        .bindText({ state.testIdAttribute ?: "" }, { state.testIdAttribute = it.trim().ifBlank { null } })
                        .applyToComponent { emptyText.text = detected ?: PlaywrightProjectLocator.DEFAULT_TEST_ID_ATTRIBUTE }
                        .comment(
                            if (detected != null) PwBundle.message("settings.testIdAttribute.detected", detected)
                            else PwBundle.message("settings.testIdAttribute.notDetected", PlaywrightProjectLocator.DEFAULT_TEST_ID_ATTRIBUTE),
                        )
                }
            }
            lateinit var showBrowserBox: JBCheckBox
            lateinit var reuseBox: JBCheckBox
            lateinit var traceBox: JBCheckBox
            group(PwBundle.message("settings.group.runs")) {
                row {
                    showBrowserBox = checkBox(PwBundle.message("toggle.showBrowser"))
                        .bindSelected({ state.showBrowser }, { settings.setShowBrowser(it) })
                        .comment(PwBundle.message("settings.showBrowser.comment"))
                        .component
                }
                row {
                    reuseBox = checkBox(PwBundle.message("toggle.reuseBrowser"))
                        .bindSelected({ state.reuseBrowser }, { settings.setReuseBrowser(it) })
                        .enabledIf(showBrowserBox.selected)
                        .comment(PwBundle.message("settings.reuseBrowser.comment"))
                        .onChanged { if (it.isSelected) traceBox.isSelected = false }
                        .component
                }
            }
            group(PwBundle.message("settings.group.trace")) {
                row {
                    traceBox = checkBox(PwBundle.message("settings.trace.always"))
                        .bindSelected({ state.alwaysRecordTrace }, { settings.setAlwaysRecordTrace(it) })
                        .comment(PwBundle.message("settings.trace.always.comment"))
                        .onChanged { if (it.isSelected) reuseBox.isSelected = false }
                        .component
                }
            }
            group(PwBundle.message("settings.group.agents")) {
                row(PwBundle.message("settings.agents.backend")) {
                    comboBox(
                        AgentBackendId.entries.toList(),
                        SimpleListCellRenderer.create("") { it.displayName },
                    ).bindItem(
                        { AgentBackendId.entries.firstOrNull { it.name == state.agentBackend } ?: AgentBackendId.CLAUDE },
                        { state.agentBackend = (it ?: AgentBackendId.CLAUDE).name },
                    )
                }
                row(PwBundle.message("settings.agents.cliPath")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile(), project)
                        .align(AlignX.FILL)
                        .bindText({ state.agentCliPath ?: "" }, { state.agentCliPath = it.ifBlank { null } })
                        .comment(PwBundle.message("settings.agents.cliPath.comment"))
                }
                row {
                    checkBox(PwBundle.message("settings.agents.codexSkipGit"))
                        .bindSelected({ state.codexSkipGitCheck }, { state.codexSkipGitCheck = it })
                }
                row(PwBundle.message("settings.agents.apiKey")) {
                    apiKeyField = passwordField()
                        .align(AlignX.FILL)
                        .comment(PwBundle.message("settings.agents.apiKey.comment"))
                        .component
                    apiKeyField.text = apiKeyLoaded ?: ""
                }
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || (::apiKeyField.isInitialized && String(apiKeyField.password) != (apiKeyLoaded ?: ""))

    override fun apply() {
        super.apply()
        if (::apiKeyField.isInitialized) {
            val key = String(apiKeyField.password)
            if (key != (apiKeyLoaded ?: "")) {
                settings.setApiKey(key)
                apiKeyLoaded = key.ifEmpty { null }
            }
        }
    }

    override fun reset() {
        super.reset()
        if (::apiKeyField.isInitialized) apiKeyField.text = apiKeyLoaded ?: ""
    }
}
