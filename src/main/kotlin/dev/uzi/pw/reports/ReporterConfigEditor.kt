/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Reads and edits the `reporter` option of a Playwright config through JS PSI. Call inside a read action on a
 * committed document. Edits are computed as the full new file text, so they can be previewed as a diff first.
 */
object ReporterConfigEditor {

    /** A reporter entry found in the config, with its string-literal options (e.g. outputFolder, resultsDir). */
    data class ExistingReporter(val name: String, val options: Map<String, String>)

    sealed class Plan {
        /** [newText] is the whole file after the edit. [added] were inserted; [present] were already configured. */
        data class Edit(val newText: String, val added: List<ReporterKind>, val present: List<ReporterKind>) : Plan()

        /** Nothing to change: every requested reporter is already configured. */
        data class UpToDate(val present: List<ReporterKind>) : Plan()

        /** The config is not a plain object literal the editor understands; [snippet] is what to add by hand. */
        data class Manual(val reason: String, val snippet: String) : Plan()
    }

    /** The config object: the argument of `defineConfig({...})`, else the object after `export default` / `module.exports =`. */
    fun findConfigObject(file: PsiFile): JSObjectLiteralExpression? {
        val calls = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
        calls.firstOrNull { (it.methodExpression as? JSReferenceExpression)?.referenceName == "defineConfig" }
            ?.argumentList?.arguments?.firstOrNull()
            ?.let { it as? JSObjectLiteralExpression }
            ?.let { return it }
        return PsiTreeUtil.findChildrenOfType(file, JSObjectLiteralExpression::class.java).firstOrNull { obj ->
            val parentText = obj.parent?.text?.trimStart() ?: return@firstOrNull false
            parentText.startsWith("export default") || parentText.startsWith("module.exports")
        }
    }

    /** The reporters configured in [file], or null when they cannot be read statically. */
    fun readReporters(file: PsiFile): List<ExistingReporter>? {
        val obj = findConfigObject(file) ?: return null
        val value = obj.findProperty("reporter")?.value ?: return emptyList()
        return when (value) {
            is JSLiteralExpression -> value.takeIf { it.isQuotedLiteral }?.stringValue?.let { listOf(ExistingReporter(it, emptyMap())) }
            is JSArrayLiteralExpression -> value.expressions.mapNotNull { entry(it) }
            else -> null
        }
    }

    /** Plans adding [kinds] to the `reporter` option of [file]. */
    fun plan(file: PsiFile, kinds: List<ReporterKind>): Plan {
        val snippet = manualSnippet(kinds)
        val obj = findConfigObject(file)
            ?: return Plan.Manual("No defineConfig({...}) or exported config object was found.", snippet)
        val text = file.text
        val property: JSProperty? = obj.findProperty("reporter")

        if (property == null) {
            val open = obj.textRange.startOffset // the '{'
            val firstProperty = obj.properties.firstOrNull()
            val indent = if (firstProperty != null) lineIndent(text, firstProperty.textRange.startOffset) else "  "
            val inner = "$indent  "
            val block = buildString {
                append("\n").append(indent).append("reporter: [\n")
                kinds.forEach { append(inner).append(it.entry).append(",\n") }
                append(indent).append("],")
            }
            val newText = text.substring(0, open + 1) + block + (if (firstProperty == null) "\n" else "") + text.substring(open + 1)
            return Plan.Edit(newText, kinds, emptyList())
        }

        val value = property.value ?: return Plan.Manual("The reporter option has no value.", snippet)
        val propertyIndent = lineIndent(text, property.textRange.startOffset)
        val inner = "$propertyIndent  "

        when (value) {
            is JSLiteralExpression -> {
                val name = value.takeIf { it.isQuotedLiteral }?.stringValue
                    ?: return Plan.Manual("The reporter option is not a string or an array.", snippet)
                val present = kinds.filter { it.configName == name }
                val toAdd = kinds - present.toSet()
                if (toAdd.isEmpty()) return Plan.UpToDate(present)
                val replacement = buildString {
                    append("[\n")
                    append(inner).append("[").append(value.text).append("],\n")
                    toAdd.forEach { append(inner).append(it.entry).append(",\n") }
                    append(propertyIndent).append("]")
                }
                val range = value.textRange
                return Plan.Edit(text.substring(0, range.startOffset) + replacement + text.substring(range.endOffset), toAdd, present)
            }

            is JSArrayLiteralExpression -> {
                val existing = value.expressions.mapNotNull { entry(it)?.name }.toSet()
                val present = kinds.filter { it.configName in existing }
                val toAdd = kinds - present.toSet()
                if (toAdd.isEmpty()) return Plan.UpToDate(present)
                val last = value.expressions.lastOrNull()
                if (last == null) {
                    val replacement = buildString {
                        append("[\n")
                        toAdd.forEach { append(inner).append(it.entry).append(",\n") }
                        append(propertyIndent).append("]")
                    }
                    val range = value.textRange
                    return Plan.Edit(text.substring(0, range.startOffset) + replacement + text.substring(range.endOffset), toAdd, present)
                }
                val lastStart = last.textRange.startOffset
                val elementIndent = if (startsLine(text, lastStart)) lineIndent(text, lastStart) else inner
                val insertion = toAdd.joinToString("") { ",\n" + elementIndent + it.entry }
                val at = last.textRange.endOffset
                return Plan.Edit(text.substring(0, at) + insertion + text.substring(at), toAdd, present)
            }

            else -> return Plan.Manual("The reporter option is computed (${value.text.take(60)}), so it can't be edited automatically.", snippet)
        }
    }

    fun manualSnippet(kinds: List<ReporterKind>): String =
        "reporter: [\n" + kinds.joinToString("") { "  ${it.entry},\n" } + "],"

    private fun entry(expression: JSExpression): ExistingReporter? = when (expression) {
        is JSLiteralExpression -> expression.takeIf { it.isQuotedLiteral }?.stringValue?.let { ExistingReporter(it, emptyMap()) }
        is JSArrayLiteralExpression -> {
            val parts = expression.expressions
            val name = (parts.getOrNull(0) as? JSLiteralExpression)?.takeIf { it.isQuotedLiteral }?.stringValue
            if (name == null) {
                null
            } else {
                val options = (parts.getOrNull(1) as? JSObjectLiteralExpression)?.properties.orEmpty().mapNotNull { p ->
                    val key = p.name ?: return@mapNotNull null
                    val v = (p.value as? JSLiteralExpression)?.takeIf { it.isQuotedLiteral }?.stringValue ?: return@mapNotNull null
                    key to v
                }.toMap()
                ExistingReporter(name, options)
            }
        }
        else -> null
    }

    private fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }

    private fun startsLine(text: CharSequence, offset: Int): Boolean =
        text.subSequence(lineStart(text, offset), offset).isBlank()
}
