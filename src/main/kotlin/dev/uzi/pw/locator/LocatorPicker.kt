/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.locator

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.HighlightParams
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.recorder.InsertionTracker
import dev.uzi.pw.recorder.RecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Pick locator (`setRecorderMode('inspecting')` → `inspectRequested { locator, ariaSnapshot }`), and highlight
 * (`highlight({ selector })` for the locator field, `highlight({ ariaTemplate })` for the aria snapshot field),
 * as playwright-vscode's src/locatorsView.ts does.
 */
@Service(Service.Level.PROJECT)
class LocatorPicker(private val project: Project, private val cs: CoroutineScope) {

    @Volatile
    var locator: String = ""
        private set

    @Volatile
    var ariaSnapshot: String = ""
        private set

    /** Error from the last `highlight({ selector })`, shown under the locator field (locatorsView.ts `_locator.error`). */
    @Volatile
    var locatorError: String? = null
        private set

    /** Error from the last `highlight({ ariaTemplate })`, shown under the aria field (locatorsView.ts `_ariaSnapshot.error`). */
    @Volatile
    var ariaError: String? = null
        private set

    private var highlightJob: Job? = null

    fun pick(config: PwConfig) {
        cs.launch {
            try {
                RecorderService.getInstance(project).startInspecting(config)
            } catch (e: PwException) {
                PwNotifications.error(project, e.message ?: "")
            }
        }
    }

    /** Called from the `inspectRequested` event. */
    fun onPicked(locator: String, ariaSnapshot: String) {
        this.locator = locator
        this.ariaSnapshot = ariaSnapshot
        this.ariaError = null
        this.locatorError = null
        // reusedBrowser.ts: copy the picked locator when "Copy on pick" is on.
        if (dev.uzi.pw.settings.PwSettings.getInstance(project).state.pickLocatorCopyToClipboard && locator.isNotEmpty()) copy(locator)
        publish()
        cs.launch(Dispatchers.EDT) {
            ToolWindowManager.getInstance(project).getToolWindow("Playwright")?.show()
        }
    }

    /** Debounced `highlight({ selector })` for edits in the locator field. Does nothing when no browser is open. */
    fun highlightDebounced(text: String) {
        locator = text
        schedule { highlightSelector(text) }
    }

    /** Debounced `highlight({ ariaTemplate })` for edits in the aria snapshot field (locatorsView.ts `ariaSnapshotChanged`). */
    fun highlightAriaDebounced(yaml: String) {
        ariaSnapshot = yaml
        schedule { highlightAria(yaml) }
    }

    /** Highlights [selector] (a locator like `getByRole('button')` or a selector); starts the browser when [config] is given. */
    fun highlight(selector: String, config: PwConfig?) {
        cs.launch {
            if (config != null) {
                try {
                    RecorderService.getInstance(project).ensureClient(config)
                } catch (e: PwException) {
                    PwNotifications.error(project, e.message ?: "")
                    return@launch
                }
            }
            highlightSelector(selector)
        }
    }

    private fun schedule(block: suspend () -> Unit) {
        highlightJob?.cancel()
        highlightJob = cs.launch {
            delay(300)
            block()
        }
    }

    private suspend fun highlightSelector(text: String) {
        val client = RecorderService.getInstance(project).currentClient() ?: return
        val error = try {
            if (text.isBlank()) client.hideHighlight() else client.highlight(HighlightParams(selector = text))
            null
        } catch (e: PwException) {
            LOG.info("highlight failed: ${e.message}")
            e.message ?: e.toString()
        }
        if (error != locatorError) {
            locatorError = error
            publish()
        }
    }

    private suspend fun highlightAria(yaml: String) {
        val client = RecorderService.getInstance(project).currentClient() ?: return
        val error = try {
            if (yaml.isBlank()) client.hideHighlight() else client.highlight(HighlightParams(ariaTemplate = yaml))
            null
        } catch (e: PwException) {
            e.message ?: e.toString()
        }
        if (error != ariaError) {
            ariaError = error
            publish()
        }
    }

    fun copy(text: String = locator) {
        if (text.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /** Inserts [text] at the caret of the selected editor. Call on the EDT. */
    fun insertAtCaret(text: String = locator) {
        if (text.isEmpty()) return
        val editor = selectedEditor() ?: return
        WriteCommandAction.runWriteCommandAction(project, PwBundle.message("locator.command.insert"), null, {
            replaceSelectionOrInsert(editor, text)
        })
    }

    /**
     * Inserts `await expect(page.<locator>).toMatchAriaSnapshot(`...`);` at the caret, indented to the caret's block
     * with [InsertionTracker]'s helpers. Call on the EDT.
     */
    fun insertAriaAssertion(locator: String = this.locator, snapshot: String = ariaSnapshot) {
        if (locator.isBlank() || snapshot.isBlank()) {
            PwNotifications.info(project, PwBundle.message("locator.info.noAria"))
            return
        }
        val editor = selectedEditor() ?: return
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val unit = psiFile?.let { CodeStyle.getIndentSize(it) } ?: 2
        val indent = InsertionTracker.guessBodyIndentation(document, line, unit)
        val before = document.charsSequence.subSequence(document.getLineStartOffset(line), offset)
        val prefix = if (before.isBlank() && column < indent) " ".repeat(indent - column) else ""
        val text = prefix + AriaSnapshotCode.assertionAt(locator, snapshot.trimEnd('\n'), indent)
        WriteCommandAction.runWriteCommandAction(project, PwBundle.message("locator.command.insertAria"), null, {
            replaceSelectionOrInsert(editor, text)
        })
    }

    private fun selectedEditor(): Editor? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) PwNotifications.info(project, PwBundle.message("locator.info.noEditor"))
        return editor
    }

    private fun replaceSelectionOrInsert(editor: Editor, text: String) {
        val caret = editor.caretModel.primaryCaret
        if (caret.hasSelection()) {
            val start = caret.selectionStart
            editor.document.replaceString(start, caret.selectionEnd, text)
            caret.removeSelection()
            caret.moveToOffset(start + text.length)
        } else {
            val start = caret.offset
            editor.document.insertString(start, text)
            caret.moveToOffset(start + text.length)
        }
    }

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(PwTopics.RECORDER_STATE).recorderStateChanged()
    }

    companion object {
        private val LOG = logger<LocatorPicker>()

        fun getInstance(project: Project): LocatorPicker = project.service()
    }
}
