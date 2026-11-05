/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.tests.CaseNode
import dev.uzi.pw.tests.DescribeNode
import dev.uzi.pw.tests.DiscoveryState
import dev.uzi.pw.tests.FileNode
import dev.uzi.pw.tests.PwTestModelListener
import dev.uzi.pw.tests.PwTestResultsListener
import dev.uzi.pw.tests.TestDiscovery
import dev.uzi.pw.tests.TestModel
import dev.uzi.pw.tests.TestNode
import dev.uzi.pw.tests.TestResults
import dev.uzi.pw.tests.TestRunActions
import dev.uzi.pw.tests.TestStatus
import dev.uzi.pw.tests.TestTopics
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The test explorer, like the VS Code Testing view: file → describe → test, with run / debug for any node and the
 * status of the last run of each test. Running goes through the bundled Playwright run configuration
 * ([TestRunActions]); statuses come from [TestResults].
 */
class TestTreePanel(private val project: Project, private val parentDisposable: Disposable) {

    private val rootNode = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(rootNode)
    private val tree = Tree(model)
    private val status = JBLabel()
    private val discovery = TestDiscovery.getInstance(project)
    private val results = TestResults.getInstance(project)
    private val repaintPending = AtomicBoolean(false)

    /** Model of the last rebuild; the first non-empty build decides the initial expansion. */
    private var shownModel: TestModel? = null

    /** True while [rebuild] restores the selection, so restoring it does not publish it again. */
    private var rebuilding = false

    /** Selected case and its status at the last check, to re-publish the selection when it finishes running. */
    private var selectedCaseStatus: Pair<String, TestStatus>? = null

    val component: JComponent

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree, true) { path -> (nodeOf(path))?.title ?: "" }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val node = nodeOf(tree.getPathForLocation(event.x, event.y)) ?: return false
                if (node is FileNode) return false
                navigate(node)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedNode()?.let { navigate(it); e.consume() }
            }
        })
        tree.addTreeSelectionListener { if (!rebuilding) publishSelection() }

        val popupGroup = DefaultActionGroup(
            RunNodeAction(debug = false),
            RunNodeAction(debug = true),
            Separator.getInstance(),
            GoToSourceAction(),
        )
        PopupHandler.installPopupMenu(tree, popupGroup, POPUP_PLACE)

        val toolbarGroup = DefaultActionGroup(
            RunAllAction(debug = false),
            RunAllAction(debug = true),
            Separator.getInstance(),
            RunNodeAction(debug = false, showText = true),
            RunNodeAction(debug = true),
            Separator.getInstance(),
            RefreshAction(),
            ExpandAllAction(),
            CollapseAllAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarGroup, true)
        toolbar.targetComponent = tree

        status.setComponentStyle(UIUtil.ComponentStyle.SMALL)
        status.foreground = UIUtil.getContextHelpForeground()
        status.border = JBUI.Borders.empty(2, 8)
        status.isVisible = false

        val north = JPanel(BorderLayout())
        north.add(toolbar.component, BorderLayout.NORTH)
        north.add(status, BorderLayout.SOUTH)

        val panel = JPanel(BorderLayout())
        panel.add(north, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        component = panel

        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(TestTopics.MODEL_CHANGED, PwTestModelListener {
            ApplicationManager.getApplication().invokeLater({ rebuild() }, { project.isDisposed })
        })
        connection.subscribe(TestTopics.RESULTS_CHANGED, PwTestResultsListener {
            if (repaintPending.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater({
                    repaintPending.set(false)
                    tree.repaint()
                    republishIfSelectedFinished()
                }, { project.isDisposed })
            }
        })

        rebuild()
        discovery.ensureStarted()
    }

    /** Rebuilds the tree from [TestDiscovery.state], keeping the expanded nodes and the selection. Runs on the EDT. */
    private fun rebuild() {
        val state = discovery.state()
        updateStatus(state)
        val testModel = state.model
        if (testModel === shownModel) return

        val expanded = TreeUtil.collectExpandedUserObjects(tree).filterIsInstance<TestNode>().map { it.key }.toSet()
        val selectedKey = selectedNode()?.key
        val firstBuild = shownModel == null || shownModel?.configPath != testModel?.configPath
        shownModel = testModel

        rebuilding = true
        try {
            rootNode.removeAllChildren()
            testModel?.files?.forEach { rootNode.add(treeNode(it)) }
            model.reload()

            if (testModel == null) return
            if (firstBuild) {
                if (testModel.caseCount <= EXPAND_ALL_LIMIT) TreeUtil.expandAll(tree)
            } else {
                expandKeys(rootNode, TreePath(rootNode), expanded)
            }
            if (selectedKey != null) {
                findPath(rootNode, TreePath(rootNode), selectedKey)?.let {
                    tree.expandPath(it.parentPath)
                    tree.selectionPath = it
                }
            }
        } finally {
            rebuilding = false
        }
    }

    private fun treeNode(node: TestNode): DefaultMutableTreeNode {
        val result = DefaultMutableTreeNode(node)
        node.children.forEach { result.add(treeNode(it)) }
        return result
    }

    private fun expandKeys(node: DefaultMutableTreeNode, path: TreePath, keys: Set<String>) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val childPath = path.pathByAddingChild(child)
            val key = (child.userObject as? TestNode)?.key ?: continue
            if (key in keys) {
                tree.expandPath(childPath)
                expandKeys(child, childPath, keys)
            }
        }
    }

    private fun findPath(node: DefaultMutableTreeNode, path: TreePath, key: String): TreePath? {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val childPath = path.pathByAddingChild(child)
            if ((child.userObject as? TestNode)?.key == key) return childPath
            findPath(child, childPath, key)?.let { return it }
        }
        return null
    }

    /** The empty text when there is nothing to show, and a one-line status above a tree that is shown. */
    private fun updateStatus(state: DiscoveryState) {
        val emptyText = tree.emptyText
        emptyText.clear()
        val config = state.config
        val testModel = state.model
        when {
            config == null -> emptyText.appendText(PwBundle.message("tests.empty.noConfig"))
            !config.isSupported -> emptyText.appendText(PwBundle.message("tests.empty.unsupported", config.displayName))
            testModel == null && state.loading -> emptyText.appendText(PwBundle.message("tests.empty.listing"))
            testModel == null && state.error != null -> {
                emptyText.appendText(PwBundle.message("tests.empty.failed"))
                emptyText.appendLine(state.error, SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                emptyText.appendLine(PwBundle.message("tests.action.refresh"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    discovery.refresh()
                }
            }
            testModel == null -> emptyText.appendText(PwBundle.message("tests.empty.listing"))
            testModel.caseCount == 0 -> emptyText.appendText(PwBundle.message("tests.empty.noTests"))
        }

        val line = when {
            testModel == null -> null
            state.loading -> PwBundle.message("tests.status.listing")
            state.error != null -> PwBundle.message("tests.status.error", state.error)
            else -> null
        }
        status.text = line.orEmpty()
        status.toolTipText = state.error
        status.isVisible = line != null
    }

    private fun nodeOf(path: TreePath?): TestNode? =
        (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TestNode

    private fun selectedNode(): TestNode? = nodeOf(tree.selectionPath)

    private fun navigate(node: TestNode) {
        val file = LocalFileSystem.getInstance().findFileByPath(node.file) ?: return
        val descriptor = if (node.line > 0) {
            OpenFileDescriptor(project, file, node.line - 1, maxOf(0, node.column - 1))
        } else {
            OpenFileDescriptor(project, file)
        }
        descriptor.navigate(true)
    }

    /** A selected test is published so the trace viewer can show its trace (playwright-vscode `_treeItemSelected`). */
    private fun publishSelection() {
        val case = selectedNode() as? CaseNode
        selectedCaseStatus = case?.let { it.key to results.status(it) }
        if (case == null) return
        val projectName = (results.lastProject(case) ?: case.projects.firstOrNull())?.takeIf { it.isNotEmpty() }
        project.messageBus.syncPublisher(PwTopics.TEST_SELECTED).testSelected(case.file, case.line, case.titlePath, projectName)
    }

    /** The selected test finished running: publish it again so a trace viewer showing it reloads the new trace. */
    private fun republishIfSelectedFinished() {
        val case = selectedNode() as? CaseNode ?: return
        val previous = selectedCaseStatus
        val now = results.status(case)
        selectedCaseStatus = case.key to now
        if (previous != null && previous.first == case.key && previous.second == TestStatus.RUNNING &&
            (now == TestStatus.PASSED || now == TestStatus.FAILED)
        ) {
            publishSelection()
        }
    }

    private fun isSupported(): Boolean = discovery.state().config?.isSupported == true

    private inner class RunAllAction(private val debug: Boolean) : DumbAwareAction(
        PwBundle.message(if (debug) "tests.action.debugAll" else "tests.action.runAll"),
        null,
        if (debug) AllIcons.Actions.StartDebugger else AllIcons.Actions.RunAll,
    ) {
        init {
            // Icon-only, like the VS Code Testing view's title bar (the text is in the tooltip).
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = isSupported()
        }

        override fun actionPerformed(e: AnActionEvent) {
            TestRunActions.run(project, null, debug)
        }
    }

    private inner class RunNodeAction(private val debug: Boolean, showText: Boolean = false) : DumbAwareAction(
        PwBundle.message(if (debug) "tests.action.debugSelected" else "tests.action.runSelected"),
        null,
        if (debug) AllIcons.Actions.StartDebugger else AllIcons.Actions.Execute,
    ) {
        init {
            // Icon-only, like the VS Code Testing view's title bar (the text is in the tooltip).
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val node = selectedNode()
            e.presentation.isEnabled = node != null && isSupported()
            if (e.place == POPUP_PLACE) {
                e.presentation.text = PwBundle.message(if (debug) "tests.action.debug" else "tests.action.run")
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedNode()?.let { TestRunActions.run(project, it, debug) }
        }
    }

    private inner class GoToSourceAction : DumbAwareAction(
        PwBundle.message("tests.action.goToSource"), null, AllIcons.Actions.EditSource,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedNode()?.let { navigate(it) }
        }
    }

    private inner class RefreshAction : DumbAwareAction(
        PwBundle.message("tests.action.refresh"), null, AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !discovery.state().loading
        }

        override fun actionPerformed(e: AnActionEvent) {
            discovery.refresh()
        }
    }

    private inner class ExpandAllAction : DumbAwareAction(
        PwBundle.message("tests.action.expandAll"), null, AllIcons.Actions.Expandall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            TreeUtil.expandAll(tree)
        }
    }

    private inner class CollapseAllAction : DumbAwareAction(
        PwBundle.message("tests.action.collapseAll"), null, AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            TreeUtil.collapseAll(tree, 0)
        }
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? TestNode ?: return
            val testStatus = results.status(node)
            icon = statusIcon(testStatus) ?: typeIcon(node)
            when (node) {
                is FileNode -> {
                    append(node.title)
                    toolTipText = node.file
                }
                is DescribeNode -> {
                    append(node.title)
                    toolTipText = null
                }
                is CaseNode -> {
                    append(node.title)
                    val projects = node.projects.filter { it.isNotEmpty() }
                    if (projects.isNotEmpty()) {
                        append("  " + projects.joinToString(", "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    toolTipText = null
                }
            }
        }

        private fun statusIcon(testStatus: TestStatus): Icon? = when (testStatus) {
            TestStatus.NONE -> null
            TestStatus.RUNNING -> AllIcons.RunConfigurations.TestState.Run
            TestStatus.PASSED -> AllIcons.RunConfigurations.TestPassed
            TestStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
            TestStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
        }

        private fun typeIcon(node: TestNode): Icon = when (node) {
            is FileNode -> FileTypeRegistry.getInstance().getFileTypeByFileName(node.file.substringAfterLast('/')).icon
                ?: AllIcons.FileTypes.Any_type
            is DescribeNode -> AllIcons.Nodes.TestGroup
            is CaseNode -> AllIcons.Nodes.Test
        }
    }

    private companion object {
        const val POPUP_PLACE = "PlaywrightTestsPopup"
        const val TOOLBAR_PLACE = "PlaywrightTestsToolbar"
        const val EXPAND_ALL_LIMIT = 200
    }
}
