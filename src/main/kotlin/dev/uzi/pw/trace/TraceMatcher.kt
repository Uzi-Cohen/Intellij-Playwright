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
 * Ported to Kotlin for Playwright Toolkit from microsoft/playwright packages/playwright/src/worker/testInfo.ts
 * (TestInfoImpl.outputDir naming), packages/utils/fileUtils.ts (sanitizeForFilePath, trimLongString) and
 * packages/playwright/src/util.ts (windowsFilesystemFriendlyLength).
 */
package dev.uzi.pw.trace

import com.intellij.openapi.util.SystemInfo
import dev.uzi.pw.core.PwProjectInfo
import java.nio.file.Path
import java.security.MessageDigest

/** Finds the trace.zip Playwright wrote for a given test, from its output folder name. */
object TraceMatcher {
    /** util.ts `windowsFilesystemFriendlyLength`. */
    private const val WINDOWS_FRIENDLY_LENGTH = 60
    private val SPEC_SUFFIX = Regex("""\.(spec|test)\.(js|ts|jsx|tsx|mjs|mts|cjs|cts)$""")
    private val UNSAFE = Regex("[\\x00-\\x2C\\x2E-\\x2F\\x3A-\\x40\\x5B-\\x60\\x7B-\\x7F]+")

    /** fileUtils.ts `sanitizeForFilePath`. */
    fun sanitizeForFilePath(s: String): String = s.replace(UNSAFE, "-")

    /** fileUtils.ts `trimLongString`. */
    fun trimLongString(s: String, length: Int = 100): String {
        if (s.length <= length) return s
        val hash = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val middle = "-${hash.substring(0, 5)}-"
        val start = (length - middle.length) / 2
        val end = length - middle.length - start
        return s.substring(0, start) + middle + s.substring(s.length - end)
    }

    /**
     * testInfo.ts `outputDir` folder name without the project id / retry / repeat suffixes:
     * `trimLongString(<file relative to testDir, no .spec.ts, separators as '-'> + '-' + sanitize(<titles>), 60)`.
     * [titles] are the titles below the file (describes and the test title).
     */
    fun folderPrefix(testDir: Path, file: Path, titles: List<String>): String {
        val relative = testDir.relativize(file).toString().replace(SPEC_SUFFIX, "")
        val sanitizedRelative = relative.replace(if (SystemInfo.isWindows) "\\" else "/", "-")
        return trimLongString(sanitizedRelative + "-" + sanitizeForFilePath(titles.joinToString(" ")), WINDOWS_FRIENDLY_LENGTH)
    }

    /**
     * The newest trace for the test among [traces]: the folder name must be the prefix, optionally followed by
     * `-<projectId>`, `-retryN`, `-repeatN`. When [projectName] is given only that project's output dir is used.
     */
    fun find(
        traces: List<TraceEntry>,
        projects: List<PwProjectInfo>,
        file: Path,
        titles: List<String>,
        projectName: String?,
    ): TraceEntry? {
        val candidates = projects.filter { projectName == null || it.name == projectName }
        val matches = traces.filter { entry ->
            val folder = entry.path.parent ?: return@filter false
            candidates.any { project ->
                if (folder.parent?.normalize() != project.outputDir.normalize()) return@any false
                if (!file.normalize().startsWith(project.testDir.normalize())) return@any false
                val prefix = folderPrefix(project.testDir.normalize(), file.normalize(), titles)
                val name = folder.fileName.toString()
                if (!name.startsWith(prefix)) return@any false
                // testInfo.ts appends `-<sanitized project id>` (the project name, `-N` for duplicates), `-retryN`, `-repeatN`.
                val projectId = sanitizeForFilePath(project.name)
                val suffix = Regex(
                    "^" + (if (projectId.isEmpty()) "" else "(-" + Regex.escape(projectId) + "(-\\d+)?)?") +
                        "(-retry\\d+)?(-repeat\\d+)?$",
                )
                suffix.matches(name.substring(prefix.length))
            }
        }
        return matches.maxByOrNull { it.mtime }
    }
}
