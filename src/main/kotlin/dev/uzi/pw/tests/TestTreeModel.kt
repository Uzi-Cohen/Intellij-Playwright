/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright 2026 Uzi Cohen
 * Ported to Kotlin for Playwright Toolkit from microsoft/playwright-vscode src/upstream/testTree.ts
 * (grouping of tests from several projects into file / describe / case items, sorting by location, and
 * status propagation). The input is the plugin's own list reporter output instead of a TeleSuite.
 */
package dev.uzi.pw.tests

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil

/** Status of a test case or group, as in upstream testTree.ts `TestItemStatus` (without `scheduled`). */
enum class TestStatus { NONE, RUNNING, PASSED, FAILED, SKIPPED }

/** A describe block of a listed test. [line] and [column] are 1-based. */
data class ListedDescribe(val title: String, val line: Int, val column: Int)

/**
 * One test of one project, as printed by `js/pw-list-reporter.cjs`. [file] is absolute (system dependent
 * separators), [titlePath] is the titles below the file: the describe titles and then the test title.
 */
data class ListedTest(
    val id: String?,
    val projectName: String,
    val file: String,
    val line: Int,
    val column: Int,
    val titlePath: List<String>,
    val describes: List<ListedDescribe>,
)

/** A project of the config. [testDir] and [outputDir] are absolute (system dependent separators), may be empty. */
data class ListedProject(val name: String, val testDir: String, val outputDir: String)

/** A node of the test tree. [file] uses `/` separators; [line] and [column] are 1-based (0 for files). */
sealed class TestNode {
    abstract val key: String
    abstract val title: String
    abstract val file: String
    abstract val line: Int
    abstract val column: Int
    abstract val children: List<TestNode>
}

class FileNode internal constructor(
    override val file: String,
    override val title: String,
) : TestNode() {
    override val key: String = "file:" + pathKey(file)
    override val line: Int = 0
    override val column: Int = 0
    internal val mutableChildren = ArrayList<TestNode>()
    override val children: List<TestNode> get() = mutableChildren
}

class DescribeNode internal constructor(
    override val key: String,
    override val title: String,
    override val file: String,
    override val line: Int,
    override val column: Int,
    /** Describe titles from the file down to this describe, inclusive. */
    val titlePath: List<String>,
) : TestNode() {
    internal val mutableChildren = ArrayList<TestNode>()
    override val children: List<TestNode> get() = mutableChildren
}

/** A test, merged across the projects that run it (upstream `TestCaseItem`). */
class CaseNode internal constructor(
    override val key: String,
    override val title: String,
    override val file: String,
    override val line: Int,
    override val column: Int,
    /** Describe titles and the test title. */
    val titlePath: List<String>,
) : TestNode() {
    internal val mutableProjects = LinkedHashSet<String>()
    internal val mutableTestIds = LinkedHashSet<String>()
    /** Names of the projects that run this test, in listing order. */
    val projects: Set<String> get() = mutableProjects
    /** Playwright test ids, one per project. */
    val testIds: Set<String> get() = mutableTestIds
    override val children: List<TestNode> get() = emptyList()
}

/** The listed tests of one config, grouped like the VS Code Testing view. */
class TestModel(
    val configPath: String,
    val projects: List<ListedProject>,
    val files: List<FileNode>,
) {
    private val casesByFile: Map<String, List<CaseNode>>
    private val nodesByKey: Map<String, TestNode>

    init {
        val byFile = HashMap<String, MutableList<CaseNode>>()
        val byKey = HashMap<String, TestNode>()
        fun visit(node: TestNode) {
            byKey[node.key] = node
            if (node is CaseNode) byFile.getOrPut(pathKey(node.file)) { ArrayList() }.add(node)
            node.children.forEach { visit(it) }
        }
        files.forEach { visit(it) }
        casesByFile = byFile
        nodesByKey = byKey
    }

    val caseCount: Int get() = casesByFile.values.sumOf { it.size }

    fun casesInFile(path: String): List<CaseNode> = casesByFile[pathKey(path)].orEmpty()

    fun node(key: String): TestNode? = nodesByKey[key]

    fun fileNode(path: String): FileNode? = nodesByKey["file:" + pathKey(path)] as? FileNode

    companion object {
        private const val SEP = "\u001e"

        /**
         * Builds the tree (upstream TestTree constructor, `hideFiles = false`): one file item per file; describes
         * with the same title under the same parent are merged; tests with the same title under the same parent
         * are merged into one case that remembers every project. Children are then sorted by location
         * (upstream `sortAndPropagateStatus`). File titles are relative to [root] when the file is under it.
         */
        fun build(configPath: String, projects: List<ListedProject>, tests: List<ListedTest>, root: String?): TestModel {
            val files = LinkedHashMap<String, FileNode>()
            val normalizedRoot = root?.let { FileUtil.toSystemIndependentName(it).trimEnd('/') }
            for (test in tests) {
                val file = FileUtil.toSystemIndependentName(test.file)
                if (file.isEmpty() || test.titlePath.isEmpty()) continue
                val fileNode = files.getOrPut(pathKey(file)) { FileNode(file, relativeTitle(file, normalizedRoot)) }

                var children: MutableList<TestNode> = fileNode.mutableChildren
                val describeTitles = ArrayList<String>()
                for (describe in test.describes) {
                    describeTitles.add(describe.title)
                    var group = children.firstOrNull { it is DescribeNode && it.title == describe.title } as DescribeNode?
                    if (group == null) {
                        group = DescribeNode(
                            key = "suite:" + pathKey(file) + SEP + describeTitles.joinToString(SEP),
                            title = describe.title,
                            file = file,
                            line = describe.line,
                            column = describe.column,
                            titlePath = describeTitles.toList(),
                        )
                        children.add(group)
                    }
                    children = group.mutableChildren
                }

                val title = test.titlePath.last()
                var case = children.firstOrNull { it is CaseNode && it.title == title } as CaseNode?
                if (case == null) {
                    case = CaseNode(
                        key = "test:" + pathKey(file) + SEP + test.titlePath.joinToString(SEP),
                        title = title,
                        file = file,
                        line = test.line,
                        column = test.column,
                        titlePath = test.titlePath,
                    )
                    children.add(case)
                }
                case.mutableProjects.add(test.projectName)
                test.id?.let { case.mutableTestIds.add(it) }
            }
            val sortedFiles = files.values.sortedBy { it.file }
            sortedFiles.forEach { sort(it) }
            return TestModel(configPath, projects, sortedFiles)
        }

        private fun sort(node: TestNode) {
            val children = when (node) {
                is FileNode -> node.mutableChildren
                is DescribeNode -> node.mutableChildren
                is CaseNode -> return
            }
            children.forEach { sort(it) }
            children.sortWith(compareBy<TestNode>({ it.line }, { it.column }))
        }

        private fun relativeTitle(file: String, root: String?): String {
            if (root != null && root.isNotEmpty() && file.startsWith("$root/")) return file.substring(root.length + 1)
            return file.substringAfterLast('/')
        }

        /**
         * Group status from child statuses (upstream `sortAndPropagateStatus`): running if any is running, else
         * failed if any failed, else skipped if all are skipped, else passed if all passed or were skipped.
         */
        fun propagate(statuses: Collection<TestStatus>): TestStatus {
            if (statuses.isEmpty()) return TestStatus.NONE
            var allPassed = true
            var allSkipped = true
            var hasFailed = false
            var hasRunning = false
            for (status in statuses) {
                allSkipped = allSkipped && status == TestStatus.SKIPPED
                allPassed = allPassed && (status == TestStatus.PASSED || status == TestStatus.SKIPPED)
                hasFailed = hasFailed || status == TestStatus.FAILED
                hasRunning = hasRunning || status == TestStatus.RUNNING
            }
            return when {
                hasRunning -> TestStatus.RUNNING
                hasFailed -> TestStatus.FAILED
                allSkipped -> TestStatus.SKIPPED
                allPassed -> TestStatus.PASSED
                else -> TestStatus.NONE
            }
        }
    }
}

/** A comparable form of a file path: `/` separators, lower case on case-insensitive file systems. */
fun pathKey(path: String): String {
    val independent = FileUtil.toSystemIndependentName(path)
    return if (SystemInfo.isFileSystemCaseSensitive) independent else independent.lowercase()
}
