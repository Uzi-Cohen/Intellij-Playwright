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
 * Ported to Kotlin for Playwright Toolkit from microsoft/playwright packages/isomorphic/codegen/javascript.ts
 * (quoteMultiline, and the 'assertSnapshot' case of JavaScriptLanguageGenerator._generateActionCall).
 */
package dev.uzi.pw.locator

import dev.uzi.pw.recorder.InsertionTracker

/** Builds the `toMatchAriaSnapshot` assertion the way Playwright's codegen does. */
object AriaSnapshotCode {

    /**
     * `await expect(page.<locator>).toMatchAriaSnapshot(<quoted snapshot>);`, matching codegen's
     * `assertSnapshot` output for Playwright Test (subject = `page`).
     */
    fun assertion(locator: String, ariaSnapshot: String): String =
        "await expect(page.$locator).toMatchAriaSnapshot(${quoteMultiline(ariaSnapshot, "  ")});"

    /** [assertion] re-indented for insertion at a caret whose block indentation is [indent] spaces. */
    fun assertionAt(locator: String, ariaSnapshot: String, indent: Int): String =
        InsertionTracker.indentBlock(assertion(locator, ariaSnapshot), indent)

    /** javascript.ts `quoteMultiline`. */
    fun quoteMultiline(text: String, indent: String = "  "): String {
        fun escape(s: String) = s.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\${", "\\\${")
        val lines = text.split("\n")
        if (lines.size == 1) return "`" + escape(text) + "`"
        return "`\n" + lines.joinToString("\n") { indent + escape(it) } + "\n$indent`"
    }
}
