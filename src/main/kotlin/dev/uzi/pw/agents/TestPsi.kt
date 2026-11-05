/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * PSI helpers for Playwright test calls, shared by the agents and the recorder. Call under a read action.
 *
 * A test call is `test(...)` / `it(...)`, `test.only|skip|fixme|fail(...)`, `test.step(...)`, or any `x.test(...)`.
 * `test.describe(...)` and hooks (`test.beforeEach`, `test.use`, ...) are not test calls.
 */
object TestPsi {
    private val TEST_NAMES = setOf("test", "it")
    private val MODIFIERS = setOf("only", "skip", "fixme", "fail")
    private const val STEP = "step"

    /** The nearest enclosing test call (not a step) that has a title and a callback, including [element] itself. */
    fun enclosingTestCall(element: PsiElement): JSCallExpression? {
        var call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java, false)
        while (call != null) {
            if (isTestCall(call) && !isStepCall(call) && testTitle(call) != null && callback(call) != null) return call
            call = PsiTreeUtil.getParentOfType(call, JSCallExpression::class.java, true)
        }
        return null
    }

    fun isTestCall(call: JSCallExpression): Boolean {
        val callee = call.methodExpression as? JSReferenceExpression ?: return false
        val name = callee.referenceName ?: return false
        val qualifier = callee.qualifier
        if (name in TEST_NAMES) return true
        if (qualifier == null) return false
        if (name !in MODIFIERS && name != STEP) return false
        val qualifierName = (qualifier as? JSReferenceExpression)?.referenceName ?: return false
        return qualifierName in TEST_NAMES
    }

    /** True for `test.step(...)`. */
    fun isStepCall(call: JSCallExpression): Boolean {
        val callee = call.methodExpression as? JSReferenceExpression ?: return false
        return callee.referenceName == STEP && callee.qualifier != null
    }

    /** The title: the string value of the first argument, or null when it isn't a plain string. */
    fun testTitle(call: JSCallExpression): String? {
        val first = call.argumentList?.arguments?.firstOrNull() ?: return null
        if (first is JSStringTemplateExpression) {
            if (first.arguments.isNotEmpty()) return null
            val text = first.text
            return if (text.length >= 2 && text.startsWith("`") && text.endsWith("`")) text.substring(1, text.length - 1) else null
        }
        val literal = first as? JSLiteralExpression ?: return null
        if (!literal.isQuotedLiteral) return null
        return literal.stringValue
    }

    /**
     * The callback of the nearest `test(...)` or `test.step(...)` call whose body contains [element]
     * (not its parameter list), or null.
     */
    fun enclosingTestCallback(element: PsiElement): JSFunction? {
        var function = PsiTreeUtil.getParentOfType(element, JSFunction::class.java, false)
        while (function != null) {
            val call = function.parent?.parent as? JSCallExpression
            if (call != null && function.parent == call.argumentList && isTestCall(call) && callback(call) == function) {
                val params = function.parameterList
                if (params == null || !PsiTreeUtil.isAncestor(params, element, false)) {
                    if (element != function) return function
                }
            }
            function = PsiTreeUtil.getParentOfType(function, JSFunction::class.java, true)
        }
        return null
    }

    /** The function-expression argument of a test call (the last function argument). */
    fun callback(call: JSCallExpression): JSFunction? =
        call.argumentList?.arguments?.lastOrNull { it is JSFunction } as? JSFunction
}
