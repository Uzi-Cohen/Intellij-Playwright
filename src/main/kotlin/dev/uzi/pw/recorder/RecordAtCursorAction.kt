/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.recorder

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.PwActionBase
import dev.uzi.pw.agents.TestPsi
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.settings.PwSettings

/** Record actions at the caret. Only available inside a `test(...)` or `test.step(...)` callback body. */
class RecordAtCursorAction : PwActionBase() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (!e.presentation.isEnabled) return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val place = e.place
        if (editor != null && file != null) {
            e.presentation.isEnabledAndVisible = isInsideTest(file, editor.caretModel.offset)
        } else {
            // Tool window button: enabled, the check happens in actionPerformed.
            e.presentation.isVisible = place != "EditorPopup"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: FileEditorManager.getInstance(project).selectedTextEditor
        start(project, editor)
    }

    companion object {
        fun start(project: Project, editor: Editor?) {
            val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported } ?: return
            val psiFile = editor?.let { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
            if (editor == null || psiFile == null || !isInsideTest(psiFile, editor.caretModel.offset)) {
                PwNotifications.info(project, PwBundle.message("recorder.info.caretNotInTest"))
                return
            }
            val url = PwSettings.getInstance(project).state.defaultRecordUrl
            RecorderService.getInstance(project).recordAtCursor(config, editor, url)
        }

        fun isInsideTest(file: PsiFile, offset: Int): Boolean {
            val element = file.findElementAt(offset) ?: file.findElementAt(maxOf(0, offset - 1)) ?: return false
            return TestPsi.enclosingTestCallback(element) != null
        }
    }
}
