/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.uzi.pw.actions.PwActionBase

/**
 * Generates tests from a plan in `specs/` with the generator agent. From the editor, the Markdown heading at or
 * above the caret (or selection start) narrows the task to that section. New `*.spec.ts` files are opened.
 */
class GenerateTestsAction : PwActionBase() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val applicable = file != null && isSpecPlan(file)
        e.presentation.isVisible = applicable
        e.presentation.isEnabled = e.presentation.isEnabled && applicable && !AgentService.getInstance(project).isRunning
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { isSpecPlan(it) } ?: return
        val service = AgentService.getInstance(project)
        val config = service.configFor(file) ?: config(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
            ?.takeIf { FileDocumentManager.getInstance().getFile(it.document) == file }
        val section = editor?.let { headingAtCaret(it) }
        val relative = VfsUtilCore.getRelativePath(file, config.workingDir) ?: file.path
        val task = buildString {
            append("Generate tests for ").append(relative)
            if (section != null) append(" section \"").append(section).append('"')
        }
        service.runAgent(AgentKind.GENERATOR, task, config)
    }

    companion object {
        private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$")

        /** A `.md` file with a `specs` directory among its ancestors. */
        fun isSpecPlan(file: VirtualFile): Boolean {
            if (file.isDirectory || !file.name.endsWith(".md", ignoreCase = true)) return false
            var dir = file.parent
            while (dir != null) {
                if (dir.name == "specs") return true
                dir = dir.parent
            }
            return false
        }

        /** The nearest Markdown heading at or above the caret line (selection start when there is a selection). */
        fun headingAtCaret(editor: Editor): String? {
            val document = editor.document
            if (document.textLength == 0) return null
            val offset = if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionStart else editor.caretModel.offset
            val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
            val text = document.charsSequence
            // Scan from the top so lines inside fenced code blocks are skipped; keep the last heading up to the caret line.
            var inFence = false
            var lastHeading: String? = null
            for (i in 0..line) {
                val lineText = text.subSequence(document.getLineStartOffset(i), document.getLineEndOffset(i)).toString()
                val trimmed = lineText.trimStart()
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence
                    continue
                }
                if (inFence) continue
                HEADING.matchEntire(lineText)?.let { lastHeading = it.groupValues[1].trim() }
            }
            return lastHeading?.takeIf { it.isNotEmpty() }
        }
    }
}
