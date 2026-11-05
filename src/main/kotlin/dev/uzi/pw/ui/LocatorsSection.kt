/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.lang.javascript.TypeScriptFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PwRecorderStateListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.locator.LocatorPicker
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The LOCATORS section, like playwright-vscode's locators view (src/locatorsView.ts): the picked locator and its
 * aria snapshot, both editable; edits highlight matching elements in the browser.
 */
class LocatorsSection(private val project: Project, private val parentDisposable: Disposable) {

    private val picker = LocatorPicker.getInstance(project)
    private val locatorField = EditorTextField("", project, TypeScriptFileType).apply { setOneLineMode(true) }
    private val ariaField = EditorTextField("", project, yamlFileType()).apply {
        setOneLineMode(false)
        preferredSize = Dimension(JBUI.scale(240), JBUI.scale(140))
        addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = false
            editor.settings.isUseSoftWraps = false
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
        }
    }
    private val ariaError = JBLabel().apply { foreground = JBColor.RED; isVisible = false }
    private var updating = false

    val component: JComponent

    init {
        listen(locatorField) { picker.highlightDebounced(it) }
        listen(ariaField) { picker.highlightAriaDebounced(it) }

        val locatorButtons = buttons(
            JButton(PwBundle.message("record.button.copy"), AllIcons.Actions.Copy).apply { addActionListener { picker.copy(locatorField.text) } },
            JButton(PwBundle.message("record.button.insert"), AllIcons.General.Add).apply { addActionListener { picker.insertAtCaret(locatorField.text) } },
        )
        val ariaButtons = buttons(
            JButton(PwBundle.message("record.button.copy"), AllIcons.Actions.Copy).apply { addActionListener { picker.copy(ariaField.text) } },
            JButton(PwBundle.message("record.button.insertAria"), AllIcons.General.Add).apply {
                toolTipText = PwBundle.message("record.button.insertAria.tooltip")
                addActionListener { picker.insertAriaAssertion(locatorField.text, ariaField.text) }
            },
        )
        component = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator(PwBundle.message("sidebar.locators")))
            .addLabeledComponent(PwBundle.message("record.label.locator"), locatorField, true)
            .addComponent(locatorButtons)
            .addLabeledComponent(PwBundle.message("record.label.aria"), ariaField, true)
            .addComponent(ariaError)
            .addComponent(ariaButtons)
            .panel
            .apply { border = JBUI.Borders.empty(0, 8, 8, 8) }

        project.messageBus.connect(parentDisposable).subscribe(PwTopics.RECORDER_STATE, PwRecorderStateListener {
            ApplicationManager.getApplication().invokeLater({ refresh() }, { project.isDisposed })
        })
        refresh()
    }

    private fun refresh() {
        updating = true
        try {
            if (locatorField.text != picker.locator) locatorField.text = picker.locator
            if (ariaField.text != picker.ariaSnapshot) ariaField.text = picker.ariaSnapshot
        } finally {
            updating = false
        }
        val error = picker.ariaError
        ariaError.text = error ?: ""
        ariaError.isVisible = !error.isNullOrBlank()
    }

    private fun listen(field: EditorTextField, onEdit: (String) -> Unit) {
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!updating) onEdit(field.text)
            }
        }
        field.addDocumentListener(listener)
        Disposer.register(parentDisposable) { field.removeDocumentListener(listener) }
    }

    private fun buttons(vararg buttons: JButton) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { buttons.forEach { add(it) } }

    companion object {
        private fun yamlFileType(): FileType {
            val type = FileTypeManager.getInstance().getFileTypeByExtension("yaml")
            return if (type is UnknownFileType || type == PlainTextFileType.INSTANCE) PlainTextFileType.INSTANCE else type
        }
    }
}
