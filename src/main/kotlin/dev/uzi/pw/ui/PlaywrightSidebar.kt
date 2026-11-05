/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.InstallBrowsersAction
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
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
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The PLAYWRIGHT section, laid out like playwright-vscode's settings view (src/settingsView.ts): TOOLS, CONFIGS,
 * PROJECTS, SETUP and SETTINGS. Rebuilt whenever configs, settings or the recorder state change.
 */
class PlaywrightSidebar(private val project: Project, parentDisposable: Disposable) {

    private val root = JPanel(BorderLayout())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = PwSettings.getInstance(project)
    private val locator = PlaywrightProjectLocator.getInstance(project)
    private val recorder = RecorderService.getInstance(project)

    @Volatile
    private var projects: List<PwProjectInfo> = emptyList()

    val component: JComponent get() = root

    init {
        Disposer.register(parentDisposable) { scope.cancel() }
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener { reloadProjects() })
        connection.subscribe(PwTopics.SETTINGS_CHANGED, PwSettingsListener { rebuildLater() })
        connection.subscribe(PwTopics.RECORDER_STATE, PwRecorderStateListener { rebuildLater() })
        rebuild()
        reloadProjects()
    }

    private fun reloadProjects() {
        scope.launch {
            val config = locator.selectedConfig()?.takeIf { it.isSupported }
            projects = if (config == null) emptyList() else try {
                locator.projects(config)
            } catch (e: PwException) {
                emptyList()
            }
            withContext(Dispatchers.EDT) { rebuild() }
        }
    }

    private fun rebuildLater() {
        ApplicationManager.getApplication().invokeLater({ rebuild() }, { project.isDisposed })
    }

    private fun rebuild() {
        root.removeAll()
        root.add(buildPanel(), BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun buildPanel(): DialogPanel {
        val config = locator.selectedConfig()
        val supported = config?.isSupported == true
        val state = recorder.state
        val busy = state == RecorderService.State.RECORDING || state == RecorderService.State.STARTING ||
            state == RecorderService.State.CODEGEN || recorder.isRunningTests
        return panel {
            group(PwBundle.message("sidebar.tools")) {
                row { link(PwBundle.message("sidebar.pickLocator"), AllIcons.General.Locate, supported) { config?.let { LocatorPicker.getInstance(project).pick(it) } } }
                row { link(PwBundle.message("sidebar.recordNew"), AllIcons.Ide.Macro.Recording_1, supported && !busy) { config?.let { RecordNewAction.start(project, it) } } }
                row {
                    link(PwBundle.message("sidebar.recordAtCursor"), AllIcons.Actions.Edit, supported && !busy) {
                        RecordAtCursorAction.start(project, FileEditorManager.getInstance(project).selectedTextEditor)
                    }
                }
                row {
                    link(PwBundle.message("sidebar.revealOutput"), AllIcons.Debugger.Console, true) {
                        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN)?.activate(null)
                    }
                }
                row { link(PwBundle.message("sidebar.closeBrowsers"), AllIcons.Actions.Close, recorder.isBrowserOpen && !recorder.isRunningTests) { recorder.closeBrowser() } }
                if (state == RecorderService.State.RECORDING || state == RecorderService.State.INSPECTING || state == RecorderService.State.CODEGEN) {
                    row {
                        comment(
                            when (state) {
                                RecorderService.State.RECORDING -> PwBundle.message("record.status.recording")
                                RecorderService.State.INSPECTING -> PwBundle.message("record.status.inspecting")
                                else -> PwBundle.message("record.status.codegen")
                            },
                        )
                    }
                    row { link(PwBundle.message("record.action.stop"), AllIcons.Actions.Suspend, true) { recorder.stop() } }
                }
            }

            group(PwBundle.message("sidebar.configs")) {
                row {
                    val configs = locator.configs()
                    val combo = ComboBox(configs.toTypedArray())
                    combo.renderer = SimpleListCellRenderer.create("") { c: PwConfig? ->
                        c?.let { "${it.displayName} (${it.version?.raw ?: "?"})" + if (it.isSupported) "" else " " + PwBundle.message("toolwindow.config.unsupported") }
                            ?: PwBundle.message("toolwindow.config.none")
                    }
                    combo.selectedItem = config
                    combo.isEnabled = configs.size > 1
                    combo.addActionListener { (combo.selectedItem as? PwConfig)?.let { if (it != locator.selectedConfig()) locator.select(it) } }
                    cell(combo).resizableColumn()
                }
            }

            group(PwBundle.message("sidebar.projects")) {
                if (config == null || !supported) {
                    row { comment(PwBundle.message("record.status.noConfig")) }
                } else if (projects.isEmpty()) {
                    row { comment(PwBundle.message("sidebar.projects.loading")) }
                } else {
                    val disabled = settings.disabledProjects(config.configFile.path)
                    for (p in projects) {
                        val name = p.name.ifEmpty { PwBundle.message("sidebar.projects.unnamed") }
                        row {
                            val box = JBCheckBox(name, p.name !in disabled)
                            box.addActionListener { settings.setProjectEnabled(config.configFile.path, p.name, box.isSelected) }
                            cell(box)
                        }
                    }
                    row {
                        link(PwBundle.message("sidebar.projects.selectAll"), null, disabled.isNotEmpty()) {
                            projects.forEach { settings.setProjectEnabled(config.configFile.path, it.name, true) }
                        }
                        link(PwBundle.message("sidebar.projects.unselectAll"), null, disabled.size < projects.size) {
                            projects.forEach { settings.setProjectEnabled(config.configFile.path, it.name, false) }
                        }
                    }
                }
            }

            group(PwBundle.message("sidebar.setup")) {
                row { link(PwBundle.message("action.installBrowsers.text"), AllIcons.Actions.Download, supported) { config?.let { InstallBrowsersAction.run(project, it) } } }
                row {
                    link(PwBundle.message("sidebar.clearCache"), AllIcons.Actions.GC, supported) {
                        config?.let { InstallBrowsersAction.runInConsole(project, it, listOf("clear-cache", "-c", it.configFile.path), PwBundle.message("sidebar.clearCache.tab")) }
                    }
                }
                row { link(PwBundle.message("reports.action.setup"), AllIcons.General.Settings, supported) { config?.let { ReportingSetup.run(project, it) } } }
            }

            group(PwBundle.message("sidebar.settings")) {
                val s = settings.state
                row {
                    val box = JBCheckBox(PwBundle.message("sidebar.showBrowser"), s.showBrowser && s.reuseBrowser)
                    box.toolTipText = PwBundle.message("sidebar.showBrowser.tooltip")
                    // playwright-vscode: one setting (reuseBrowser) = headed + reused browser + one worker.
                    box.addActionListener {
                        settings.setShowBrowser(box.isSelected)
                        settings.setReuseBrowser(box.isSelected)
                    }
                    cell(box)
                    comment(PwBundle.message("sidebar.showBrowser.hint"))
                }
                row {
                    val box = JBCheckBox(PwBundle.message("sidebar.showTrace"), s.alwaysRecordTrace)
                    box.toolTipText = PwBundle.message("sidebar.showTrace.tooltip")
                    // playwright-vscode settingsModel.ts: show trace and show browser turn each other off.
                    box.addActionListener {
                        settings.setAlwaysRecordTrace(box.isSelected)
                        if (box.isSelected) settings.setShowBrowser(false)
                    }
                    cell(box)
                }
                row(PwBundle.message("sidebar.updateSnapshots")) {
                    val combo = ComboBox(UpdateSnapshotsRunPatch.SNAPSHOT_MODES.toTypedArray())
                    combo.renderer = SimpleListCellRenderer.create("") { it?.let { mode -> modeLabel(mode) } ?: "" }
                    combo.selectedItem = s.updateSnapshots ?: UpdateSnapshotsRunPatch.NO_OVERRIDE
                    combo.addActionListener { (combo.selectedItem as? String)?.let { settings.setUpdateSnapshots(it) } }
                    cell(combo)
                }
                row(PwBundle.message("sidebar.updateMethod")) {
                    val combo = ComboBox(UpdateSnapshotsRunPatch.SOURCE_METHODS.toTypedArray())
                    combo.renderer = SimpleListCellRenderer.create("") { it?.let { mode -> modeLabel(mode) } ?: "" }
                    combo.selectedItem = s.updateSourceMethod ?: UpdateSnapshotsRunPatch.NO_OVERRIDE
                    combo.addActionListener { (combo.selectedItem as? String)?.let { settings.setUpdateSourceMethod(it) } }
                    cell(combo)
                }
            }
        }.apply { border = JBUI.Borders.empty(0, 8) }
    }

    private fun modeLabel(mode: String): String =
        if (mode == UpdateSnapshotsRunPatch.NO_OVERRIDE) PwBundle.message("sidebar.noOverride") else mode

    /** An [ActionLink] with an optional icon, disabled when [enabled] is false. */
    private fun com.intellij.ui.dsl.builder.Row.link(text: String, icon: javax.swing.Icon?, enabled: Boolean, onClick: () -> Unit) {
        val link = ActionLink(text) { onClick() }
        if (icon != null) link.icon = icon
        link.isEnabled = enabled
        cell(link)
    }
}
