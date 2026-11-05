/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.PwActionBase
import dev.uzi.pw.core.PwNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the healer agent on one failing test and shows what it changed (PLAN 9.1). */
object Healer {
    private val LOG = logger<Healer>()

    /** Starts healing [title] in [file]. Call from any thread. */
    fun heal(project: Project, file: VirtualFile, title: String) {
        val service = AgentService.getInstance(project)
        val config = service.configFor(file)
        if (config == null) {
            PwNotifications.warn(project, PwBundle.message("agents.unavailable.noConfig"))
            return
        }
        if (service.isRunning) {
            PwNotifications.warn(project, PwBundle.message("agents.alreadyRunning"))
            return
        }
        service.scope.launch {
            val before = readAction { FileDocumentManager.getInstance().getDocument(file)?.text }
            val label = withContext(Dispatchers.IO) {
                LocalHistory.getInstance().putSystemLabel(project, PwBundle.message("agents.heal.label", title))
            }
            val relative = VfsUtilCore.getRelativePath(file, config.workingDir) ?: file.path
            val task = "Fix the failing test \"$title\" in $relative"
            val options = AgentService.RunOptions(
                trackChanges = true,
                snapshot = if (before != null) mapOf(file.path to before) else emptyMap(),
                onFinished = { result -> showChanges(project, config.workingDir, label, result) },
            )
            service.runAgent(AgentKind.HEALER, task, config, options)
        }
    }

    private suspend fun showChanges(project: Project, root: VirtualFile, label: Label, result: AgentService.RunResult) {
        val factory = DiffContentFactory.getInstance()
        val fs = LocalFileSystem.getInstance()
        val requests = mutableListOf<SimpleDiffRequest>()
        val paths = LinkedHashSet<String>().apply {
            addAll(result.before.keys)
            addAll(result.changed)
            addAll(result.created)
        }
        for (path in paths) {
            val file = fs.findFileByPath(path)?.takeIf { it.isValid && !it.isDirectory }
            val fileType = file?.fileType ?: FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
            if (fileType.isBinary) continue
            val current = file?.let { f -> readAction { FileDocumentManager.getInstance().getDocument(f)?.text } }
            val wasCreated = path in result.created && path !in result.before
            var beforeText: String? = if (wasCreated) null else result.before[path]
            if (!wasCreated && (beforeText == null || (beforeText == current && path in result.changed))) {
                labelText(label, path, file)?.let { beforeText = it }
            }
            if (beforeText == current) continue
            if (beforeText == null && current == null) continue

            val beforeContent: DiffContent = beforeText?.let { factory.create(project, it, fileType) } ?: factory.createEmpty()
            val afterContent: DiffContent = file?.let { factory.create(project, it) } ?: factory.createEmpty()
            val relative = file?.let { VfsUtilCore.getRelativePath(it, root) } ?: path
            requests += SimpleDiffRequest(
                PwBundle.message("agents.heal.diffTitle", relative),
                beforeContent, afterContent,
                PwBundle.message("agents.heal.before"), PwBundle.message("agents.heal.after"),
            )
        }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            when {
                requests.isEmpty() -> PwNotifications.info(project, PwBundle.message("agents.heal.noChanges"))
                requests.size == 1 -> DiffManager.getInstance().showDiff(project, requests.single(), DiffDialogHints.DEFAULT)
                else -> DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
            }
        }
    }

    private fun labelText(label: Label, path: String, file: VirtualFile?): String? = try {
        val content = label.getByteContent(path)
        val bytes = content?.bytes
        if (content == null || content.isDirectory || bytes == null) null
        else if (file != null) LoadTextUtil.getTextByBinaryPresentation(bytes, file).toString()
        else String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        LOG.debug("No local history content for $path", e)
        null
    }

    /** The test call around the caret: file and title, or null. Call under a read action. */
    fun testAtCaret(psiFile: PsiFile, editor: Editor): Pair<VirtualFile, String>? {
        val file = psiFile.virtualFile ?: return null
        val element = psiFile.findElementAt(editor.caretModel.offset) ?: return null
        val call = TestPsi.enclosingTestCall(element) ?: return null
        val title = TestPsi.testTitle(call) ?: return null
        return file to title
    }
}

/**
 * "Heal with Playwright Agent": in the test tree popup on a failed test, and in the editor popup on a `test(...)` call.
 */
class HealTestAction : PwActionBase() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        val proxy = e.getData(AbstractTestProxy.DATA_KEY)
        if (proxy != null) {
            val ok = proxy is SMTestProxy && proxy.isDefect && proxy.isLeaf && !proxy.isSuite
            e.presentation.isVisible = true
            e.presentation.isEnabled = e.presentation.isEnabled && ok && !AgentService.getInstance(project).isRunning
            return
        }
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val target = if (psiFile != null && editor != null) Healer.testAtCaret(psiFile, editor) else null
        e.presentation.isVisible = target != null
        e.presentation.isEnabled = e.presentation.isEnabled && target != null && !AgentService.getInstance(project).isRunning
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val proxy = e.getData(AbstractTestProxy.DATA_KEY)
        if (proxy is SMTestProxy) {
            AgentService.getInstance(project).scope.launch {
                val target = smartReadAction(project) {
                    val element = proxy.getLocation(project, GlobalSearchScope.projectScope(project))?.psiElement
                        ?: return@smartReadAction null
                    val file = element.containingFile?.virtualFile ?: return@smartReadAction null
                    val title = TestPsi.enclosingTestCall(element)?.let { TestPsi.testTitle(it) } ?: proxy.name
                    file to title
                }
                if (target == null) {
                    PwNotifications.warn(project, PwBundle.message("agents.heal.noLocation", proxy.name))
                } else {
                    Healer.heal(project, target.first, target.second)
                }
            }
            return
        }
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val target = Healer.testAtCaret(psiFile, editor) ?: return
        Healer.heal(project, target.first, target.second)
    }
}
