/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.uzi.pw.PwBundle

/**
 * Gutter icon on a `test(...)` call whose file and title are in [FailedTestsRegistry]. Clicking it heals the test.
 * The marker sits on the first leaf of the callee (`test` in `test(...)` and `test.only(...)`).
 */
class HealLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val text = element.text
        if (text != "test" && text != "it") return null
        val call = calleeCall(element) ?: return null
        if (!TestPsi.isTestCall(call) || TestPsi.isStepCall(call)) return null
        val title = TestPsi.testTitle(call) ?: return null
        val path = element.containingFile?.virtualFile?.path ?: return null
        val project = element.project
        if (!FailedTestsRegistry.getInstance(project).isFailed(path, title)) return null

        val handler = GutterIconNavigationHandler<PsiElement> { _, elt ->
            val file = elt.containingFile?.virtualFile ?: return@GutterIconNavigationHandler
            Healer.heal(elt.project, file, title)
        }
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.QuickfixBulb,
            { PwBundle.message("agents.heal.gutter", title) },
            handler,
            GutterIconRenderer.Alignment.RIGHT,
            { PwBundle.message("agents.heal.gutterName") },
        )
    }

    /** The call whose callee starts with [leaf], or null. */
    private fun calleeCall(leaf: PsiElement): JSCallExpression? {
        val call = PsiTreeUtil.getParentOfType(leaf, JSCallExpression::class.java, true) ?: return null
        val callee = call.methodExpression ?: return null
        if (!PsiTreeUtil.isAncestor(callee, leaf, false)) return null
        return if (PsiTreeUtil.getDeepestFirst(callee) == leaf) call else null
    }
}
