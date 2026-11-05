/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.locator

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.HighlightParams
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.recorder.RecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Pick locator (`setRecorderMode('inspecting')` → `inspectRequested`) and highlight (`highlight({ selector })`).
 * The picked locator is shown in the Record tab, where edits are highlighted live.
 */
@Service(Service.Level.PROJECT)
class LocatorPicker(private val project: Project, private val cs: CoroutineScope) {

    @Volatile
    var locator: String = ""
        private set

    @Volatile
    var ariaSnapshot: String = ""
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
        publish()
        cs.launch(Dispatchers.EDT) {
            ToolWindowManager.getInstance(project).getToolWindow("Playwright")?.show()
        }
    }

    /** Debounced highlight of [text] (from the editable field). Does nothing when no browser is open. */
    fun highlightDebounced(text: String) {
        locator = text
        highlightJob?.cancel()
        highlightJob = cs.launch {
            delay(300)
            highlightNow(text)
        }
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
            highlightNow(selector)
        }
    }

    private suspend fun highlightNow(text: String) {
        val client = RecorderService.getInstance(project).currentClient() ?: return
        try {
            if (text.isBlank()) client.hideHighlight() else client.highlight(HighlightParams(selector = text))
        } catch (e: PwException) {
            LOG.info("highlight failed: ${e.message}")
        }
    }

    fun copy(text: String = locator) {
        if (text.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /** Inserts [text] at the caret of the selected editor. Call on the EDT. */
    fun insertAtCaret(text: String = locator) {
        if (text.isEmpty()) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            PwNotifications.info(project, PwBundle.message("locator.info.noEditor"))
            return
        }
        WriteCommandAction.runWriteCommandAction(project, PwBundle.message("locator.command.insert"), null, {
            val caret = editor.caretModel.primaryCaret
            if (caret.hasSelection()) {
                editor.document.replaceString(caret.selectionStart, caret.selectionEnd, text)
                caret.moveToOffset(caret.selectionStart + text.length)
            } else {
                editor.document.insertString(caret.offset, text)
                caret.moveToOffset(caret.offset + text.length)
            }
        })
    }

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(PwTopics.RECORDER_STATE).recorderStateChanged()
    }

    companion object {
        private val LOG = logger<LocatorPicker>()

        fun getInstance(project: Project): LocatorPicker = project.service()
    }
}
