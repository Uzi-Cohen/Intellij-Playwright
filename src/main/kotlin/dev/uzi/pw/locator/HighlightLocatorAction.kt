/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.locator

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.uzi.pw.actions.PwActionBase

/** Editor action: highlight the locator expression under the caret in the browser. */
class HighlightLocatorAction : PwActionBase() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (!e.presentation.isEnabled) return
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = file != null && editor != null &&
            locatorAt(file, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val locator = locatorAt(file, editor.caretModel.offset) ?: return
        LocatorPicker.getInstance(project).highlight(locator, config(e))
    }

    companion object {
        private val LOCATOR_METHODS = setOf(
            "locator", "getByRole", "getByText", "getByLabel", "getByPlaceholder", "getByAltText",
            "getByTitle", "getByTestId", "frameLocator", "contentFrame", "first", "last", "nth", "filter", "and", "or",
        )

        /**
         * The locator chain around [offset], without its receiver: for `page.getByRole('button').first()` it
         * returns `getByRole('button').first()`.
         */
        fun locatorAt(file: PsiFile, offset: Int): String? {
            val leaf: PsiElement = file.findElementAt(offset) ?: file.findElementAt(maxOf(0, offset - 1)) ?: return null
            var call = PsiTreeUtil.getParentOfType(leaf, JSCallExpression::class.java, false) ?: return null
            if (!isLocatorCall(call)) {
                call = generateSequence(call) { PsiTreeUtil.getParentOfType(it, JSCallExpression::class.java, true) }
                    .firstOrNull { isLocatorCall(it) } ?: return null
            }
            // Walk outward while the parent continues the chain (`<call>.first()`).
            var outer: JSCallExpression = call
            while (true) {
                val ref = outer.parent as? JSReferenceExpression ?: break
                val next = ref.parent as? JSCallExpression ?: break
                if (next.methodExpression != ref || ref.referenceName !in LOCATOR_METHODS) break
                outer = next
            }
            val receiver = receiverOf(outer) ?: return null
            val text = outer.text
            val receiverText = receiver.text
            val stripped = if (text.startsWith(receiverText)) text.substring(receiverText.length).trimStart().removePrefix(".") else text
            return stripped.replace(Regex("""\s*\n\s*"""), "").trim().ifEmpty { null }
        }

        private fun isLocatorCall(call: JSCallExpression): Boolean =
            (call.methodExpression as? JSReferenceExpression)?.referenceName in LOCATOR_METHODS

        /** The innermost qualifier that is not itself a locator call (e.g. `page`, `this.page`, `frame`). */
        private fun receiverOf(outer: JSCallExpression): JSExpression? {
            var current: JSExpression = outer
            while (true) {
                val call = current as? JSCallExpression ?: return current
                val ref = call.methodExpression as? JSReferenceExpression ?: return current
                if (ref.referenceName !in LOCATOR_METHODS) return current
                current = ref.qualifier ?: return null
            }
        }
    }
}
