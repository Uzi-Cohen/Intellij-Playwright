/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.lang.javascript.TypeScriptFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwRecorderStateListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.locator.LocatorPicker
import dev.uzi.pw.recorder.RecordAtCursorAction
import dev.uzi.pw.recorder.RecordNewAction
import dev.uzi.pw.recorder.RecorderService
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Record tab: record new / at cursor, pick locator, the editable locator and aria snapshot fields
 * (each highlighted live in the browser, as in playwright-vscode's locators view), and browser controls.
 */
class RecordTab(private val project: Project, private val parentDisposable: Disposable) {

    private val recorder = RecorderService.getInstance(project)
    private val picker = LocatorPicker.getInstance(project)
    private val status = JBLabel()
    private val locatorField = EditorTextField("", project, TypeScriptFileType)
    private val ariaField = EditorTextField("", project, yamlFileType()).apply {
        setOneLineMode(false)
        preferredSize = Dimension(JBUI.scale(300), JBUI.scale(160))
        addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = false
            editor.settings.isUseSoftWraps = false
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
        }
    }
    private val ariaError = JBLabel().apply { foreground = JBColor.RED; isVisible = false }
    private var updatingFields = false

    val component: JComponent

    init {
        locatorField.setOneLineMode(true)
        listen(locatorField) { picker.highlightDebounced(it) }
        listen(ariaField) { picker.highlightAriaDebounced(it) }

        val locatorButtons = buttons(
            JButton(PwBundle.message("record.button.copy"), AllIcons.Actions.Copy).apply {
                addActionListener { picker.copy(locatorField.text) }
            },
            JButton(PwBundle.message("record.button.insert"), AllIcons.General.Add).apply {
                addActionListener { picker.insertAtCaret(locatorField.text) }
            },
        )
        val ariaButtons = buttons(
            JButton(PwBundle.message("record.button.copy"), AllIcons.Actions.Copy).apply {
                addActionListener { picker.copy(ariaField.text) }
            },
            JButton(PwBundle.message("record.button.insertAria"), AllIcons.General.Add).apply {
                toolTipText = PwBundle.message("record.button.insertAria.tooltip")
                addActionListener { picker.insertAriaAssertion(locatorField.text, ariaField.text) }
            },
        )

        val body = FormBuilder.createFormBuilder()
            .addComponent(status)
            .addLabeledComponent(PwBundle.message("record.label.locator"), locatorField, true)
            .addComponent(locatorButtons)
            .addLabeledComponent(PwBundle.message("record.label.aria"), ariaField, true)
            .addComponent(ariaError)
            .addComponent(ariaButtons)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        body.border = JBUI.Borders.empty(8)

        val group = DefaultActionGroup().apply {
            add(TabAction(PwBundle.message("record.action.recordNew"), AllIcons.Actions.StartDebugger, { canStart() }) {
                PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported }?.let { RecordNewAction.start(project, it) }
            })
            add(TabAction(PwBundle.message("record.action.recordAtCursor"), AllIcons.Actions.Edit, { canStart() }) {
                RecordAtCursorAction.start(project, FileEditorManager.getInstance(project).selectedTextEditor)
            })
            add(TabAction(PwBundle.message("record.action.pick"), AllIcons.General.Locate, { canStart() || recorder.state == RecorderService.State.INSPECTING }) {
                PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported }?.let { picker.pick(it) }
            })
            addSeparator()
            add(TabAction(PwBundle.message("record.action.stop"), AllIcons.Actions.Suspend, {
                recorder.state == RecorderService.State.RECORDING || recorder.state == RecorderService.State.INSPECTING ||
                    recorder.state == RecorderService.State.CODEGEN
            }) { recorder.stop() })
            add(TabAction(PwBundle.message("record.action.closeBrowser"), AllIcons.Actions.Close, { recorder.isBrowserOpen && !recorder.isRunningTests }) {
                recorder.closeBrowser()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("PlaywrightRecordTab", group, true)

        component = SimpleToolWindowPanel(true, true).apply {
            toolbar.targetComponent = this
            setToolbar(toolbar.component)
            setContent(body)
        }

        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(PwTopics.RECORDER_STATE, PwRecorderStateListener { refreshLater() })
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener { refreshLater() })
        refresh()
    }

    private fun listen(field: EditorTextField, onEdit: (String) -> Unit) {
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!updatingFields) onEdit(field.text)
            }
        }
        field.addDocumentListener(listener)
        Disposer.register(parentDisposable) { field.removeDocumentListener(listener) }
    }

    private fun buttons(vararg buttons: JButton) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        buttons.forEach { add(it) }
    }

    private fun canStart(): Boolean {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()
        val state = recorder.state
        return config?.isSupported == true && !recorder.isRunningTests && state != RecorderService.State.RECORDING &&
            state != RecorderService.State.STARTING && state != RecorderService.State.CODEGEN
    }

    private fun refreshLater() {
        ApplicationManager.getApplication().invokeLater({ refresh() }, { project.isDisposed })
    }

    private fun refresh() {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()
        status.text = when {
            config == null -> PwBundle.message("record.status.noConfig")
            !config.isSupported -> PwBundle.message("record.status.unsupported", config.version?.raw ?: "?")
            else -> when (recorder.state) {
                RecorderService.State.IDLE -> when {
                    recorder.isRunningTests -> PwBundle.message("record.status.runningTests")
                    recorder.isBrowserOpen -> PwBundle.message("record.status.browserOpen", recorder.pageCount)
                    else -> PwBundle.message("record.status.idle")
                }
                RecorderService.State.STARTING -> PwBundle.message("record.status.starting")
                RecorderService.State.RECORDING -> PwBundle.message("record.status.recording")
                RecorderService.State.INSPECTING -> PwBundle.message("record.status.inspecting")
                RecorderService.State.CODEGEN -> PwBundle.message("record.status.codegen")
            }
        }
        updatingFields = true
        try {
            if (locatorField.text != picker.locator) locatorField.text = picker.locator
            if (ariaField.text != picker.ariaSnapshot) ariaField.text = picker.ariaSnapshot
        } finally {
            updatingFields = false
        }
        val error = picker.ariaError
        ariaError.text = error ?: ""
        ariaError.isVisible = !error.isNullOrBlank()
    }

    private class TabAction(
        text: String,
        icon: javax.swing.Icon,
        private val enabled: () -> Boolean,
        private val perform: () -> Unit,
    ) : DumbAwareAction(text, text, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    companion object {
        /** The YAML file type when the bundled YAML plugin is enabled, plain text otherwise. */
        private fun yamlFileType(): FileType {
            val type = FileTypeManager.getInstance().getFileTypeByExtension("yaml")
            return if (type is UnknownFileType || type == PlainTextFileType.INSTANCE) PlainTextFileType.INSTANCE else type
        }
    }
}
