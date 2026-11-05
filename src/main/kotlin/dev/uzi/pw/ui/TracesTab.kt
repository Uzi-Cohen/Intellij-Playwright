/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.core.PwTracesListener
import dev.uzi.pw.trace.TraceEntry
import dev.uzi.pw.trace.TraceIndex
import dev.uzi.pw.trace.TraceService
import dev.uzi.pw.trace.TraceViewerPanel
import dev.uzi.pw.trace.TraceWatcher
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** The "Traces" tab: traces grouped by test on the left, the trace viewer on the right. */
class TracesTab(private val project: Project, private val parentDisposable: Disposable) {

    private class GroupNode(val testName: String, val count: Int) : DefaultMutableTreeNode(testName)
    private class TraceNode(val entry: TraceEntry) : DefaultMutableTreeNode(entry)

    private val rootNode = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(rootNode)
    private val tree = Tree(model)
    private val viewer = TraceViewerPanel(project, parentDisposable)

    val component: JComponent

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        tree.emptyText.text = PwBundle.message("trace.empty")
        TreeSpeedSearch.installOn(tree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val entry = entryAt(tree.getPathForLocation(event.x, event.y)) ?: return false
                openTrace(entry)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedEntry()?.let { openTrace(it); e.consume() }
            }
        })

        val popupGroup = DefaultActionGroup(OpenAction(), RevealAction(), DeleteAction())
        PopupHandler.installPopupMenu(tree, popupGroup, POPUP_PLACE)

        val toolbarGroup = DefaultActionGroup(OpenAction(), RefreshAction(), DeleteAction())
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarGroup, true)
        toolbar.targetComponent = tree

        val left = JPanel(BorderLayout())
        left.add(toolbar.component, BorderLayout.NORTH)
        left.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, SPLITTER_KEY, 0.3f)
        splitter.firstComponent = left
        splitter.secondComponent = viewer.component
        component = splitter

        project.messageBus.connect(parentDisposable).subscribe(PwTopics.TRACES_CHANGED, PwTracesListener {
            ApplicationManager.getApplication().invokeLater({ rebuild() }, { project.isDisposed })
        })

        rebuild()
        TraceWatcher.getInstance(project).ensureStarted()
    }

    /** Rebuilds the tree from [TraceIndex], keeping the selection and the expanded groups. Runs on the EDT. */
    private fun rebuild() {
        val selectedPath = selectedEntry()?.path
        val expanded = TreeUtil.collectExpandedUserObjects(tree).filterIsInstance<String>().toSet()
        val firstBuild = rootNode.childCount == 0

        rootNode.removeAllChildren()
        for ((testName, entries) in TraceIndex.getInstance(project).grouped()) {
            val group = GroupNode(testName, entries.size)
            entries.forEach { group.add(TraceNode(it)) }
            rootNode.add(group)
        }
        model.reload()

        var toSelect: TreePath? = null
        for (i in 0 until rootNode.childCount) {
            val group = rootNode.getChildAt(i) as GroupNode
            val groupPath = TreePath(arrayOf<Any>(rootNode, group))
            if (firstBuild || group.testName in expanded || i == 0) tree.expandPath(groupPath)
            for (j in 0 until group.childCount) {
                val node = group.getChildAt(j) as TraceNode
                if (node.entry.path == selectedPath) toSelect = groupPath.pathByAddingChild(node)
            }
        }
        toSelect?.let {
            tree.expandPath(it.parentPath)
            tree.selectionPath = it
        }
    }

    private fun entryAt(path: TreePath?): TraceEntry? {
        return when (val node = path?.lastPathComponent) {
            is TraceNode -> node.entry
            is GroupNode -> (node.firstChild as? TraceNode)?.entry
            else -> null
        }
    }

    private fun selectedEntry(): TraceEntry? = entryAt(tree.selectionPath)

    private fun openTrace(entry: TraceEntry) {
        TraceService.getInstance(project).open(entry.path)
    }

    private fun deleteTrace(entry: TraceEntry) {
        val answer = Messages.showYesNoDialog(
            project,
            PwBundle.message("trace.delete.confirm", entry.path.toString()),
            PwBundle.message("trace.delete.title"),
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Files.deleteIfExists(entry.path)
                entry.path.parent?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)?.refresh(true, false) }
                if (TraceIndex.getInstance(project).remove(entry.path)) {
                    project.messageBus.syncPublisher(PwTopics.TRACES_CHANGED).tracesChanged()
                }
            } catch (e: Exception) {
                LOG.warn("Cannot delete ${entry.path}", e)
                PwNotifications.error(project, PwBundle.message("trace.delete.failed", entry.path.toString(), e.message ?: ""))
            }
            TraceWatcher.getInstance(project).rescan()
        }
    }

    private inner class OpenAction : DumbAwareAction(
        PwBundle.message("trace.action.open"), null, AllIcons.Actions.Show,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedEntry() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedEntry()?.let { openTrace(it) }
        }
    }

    private inner class RevealAction : DumbAwareAction(RevealFileAction.getActionName()) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedEntry() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedEntry()?.let { RevealFileAction.openFile(it.path) }
        }
    }

    private inner class DeleteAction : DumbAwareAction(
        PwBundle.message("trace.action.delete"), null, AllIcons.General.Delete,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tree.selectionPath?.lastPathComponent is TraceNode
        }

        override fun actionPerformed(e: AnActionEvent) {
            (tree.selectionPath?.lastPathComponent as? TraceNode)?.let { deleteTrace(it.entry) }
        }
    }

    private inner class RefreshAction : DumbAwareAction(
        PwBundle.message("trace.action.refresh"), null, AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            TraceWatcher.getInstance(project).rescan()
        }
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (value) {
                is GroupNode -> {
                    icon = AllIcons.Nodes.Test
                    append(value.testName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is TraceNode -> {
                    val entry = value.entry
                    icon = AllIcons.FileTypes.Archive
                    append(DateFormatUtil.formatPrettyDateTime(entry.mtime))
                    entry.projectName?.let { append("  $it", SimpleTextAttributes.GRAY_ATTRIBUTES) }
                    if (entry.retry > 0) {
                        append("  " + PwBundle.message("trace.node.retry", entry.retry), SimpleTextAttributes.GRAY_ATTRIBUTES)
                    }
                    toolTipText = entry.path.toString()
                }
            }
        }
    }

    private companion object {
        val LOG = logger<TracesTab>()
        const val POPUP_PLACE = "PlaywrightTracesPopup"
        const val TOOLBAR_PLACE = "PlaywrightTracesToolbar"
        const val SPLITTER_KEY = "Playwright.Traces.Splitter"
    }
}
