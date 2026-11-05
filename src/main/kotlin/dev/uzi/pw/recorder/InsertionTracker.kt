/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright 2026 Uzi Cohen
 * Ported to Kotlin for Playwright for IntelliJ from microsoft/playwright-vscode src/reusedBrowser.ts
 * (guessIndentation, indentBlock, and the sourceChanged editing logic). Instead of re-selecting the last
 * action, the whole recorded block is tracked with a RangeMarker and rewritten from the full action list.
 */
package dev.uzi.pw.recorder

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker

/**
 * Tracks the block of recorded actions in [document]. Each [replace] rewrites the whole block from the
 * full `sourceChanged.actions` list (the server re-sends every action each time). Call it inside a write command.
 *
 * @param indent target indentation (spaces) of every action line.
 * @param firstLinePrefix text inserted before the first action, to bring the caret's column up to [indent].
 */
class InsertionTracker(
    val document: Document,
    start: Int,
    end: Int,
    private val indent: Int,
    private val firstLinePrefix: String,
) {
    private var marker: RangeMarker = document.createRangeMarker(start, end)

    val isValid: Boolean get() = marker.isValid

    val startOffset: Int get() = marker.startOffset

    /** Replaces the tracked block with [actions]. Returns false when the block was lost (e.g. deleted by the user). */
    fun replace(actions: List<String>): Boolean {
        if (!marker.isValid) return false
        val text = render(actions)
        val start = marker.startOffset
        val end = marker.endOffset
        if (document.charsSequence.subSequence(start, end).toString() == text) return true
        document.replaceString(start, end, text)
        marker.dispose()
        marker = document.createRangeMarker(start, start + text.length)
        return true
    }

    fun dispose() {
        marker.dispose()
    }

    private fun render(actions: List<String>): String {
        if (actions.isEmpty()) return firstLinePrefix
        val pad = " ".repeat(indent)
        return actions.mapIndexed { i, action ->
            val block = indentBlock(action, indent)
            if (i == 0) firstLinePrefix + block else "\n" + pad + block
        }.joinToString("")
    }

    companion object {
        /** reusedBrowser.ts `guessIndentation`: indentation of the nearest non-blank line at or above [line]. */
        fun guessIndentation(document: Document, line: Int): Int {
            val text = document.charsSequence
            for (i in line downTo 0) {
                val start = document.getLineStartOffset(i)
                val end = document.getLineEndOffset(i)
                val lineText = text.subSequence(start, end)
                if (lineText.isNotBlank()) return lineText.indexOfFirst { !it.isWhitespace() }
            }
            return 0
        }

        /**
         * Like [guessIndentation], but adds [indentUnit] when the nearest non-blank line above opens a block
         * (ends with `{`), so recording into an empty test body is indented one level deeper.
         */
        fun guessBodyIndentation(document: Document, line: Int, indentUnit: Int): Int {
            val text = document.charsSequence
            for (i in line downTo 0) {
                val start = document.getLineStartOffset(i)
                val end = document.getLineEndOffset(i)
                val lineText = text.subSequence(start, end).toString()
                if (lineText.isNotBlank()) {
                    val base = lineText.indexOfFirst { !it.isWhitespace() }
                    return if (i < line && lineText.trimEnd().endsWith("{")) base + indentUnit else base
                }
            }
            return 0
        }

        /** reusedBrowser.ts `indentBlock`: first line trimmed, following lines shifted to [indent]. */
        fun indentBlock(block: String, indent: Int): String {
            val lines = block.split("\n")
            if (lines.isEmpty()) return block
            val blockIndent = lines[0].takeWhile { it.isWhitespace() }.length
            val shift = " ".repeat(maxOf(0, indent - blockIndent))
            return lines.mapIndexed { i, l -> if (i > 0) shift + l else l.trimStart() }.joinToString("\n")
        }
    }
}
