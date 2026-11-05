/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/**
 * "Set up reporting": adds reporters to `playwright.config.*` (diff preview first, undoable), updates `.gitignore`,
 * and installs the npm packages a reporter needs with the project's package manager. Call [run] on the EDT.
 */
object ReportingSetup {

    fun run(project: Project, config: PwConfig) {
        val psiDocuments = PsiDocumentManager.getInstance(project)
        psiDocuments.commitAllDocuments()
        val psiFile = PsiManager.getInstance(project).findFile(config.configFile)
        val document = FileDocumentManager.getInstance().getDocument(config.configFile)
        if (psiFile == null || document == null) {
            PwNotifications.error(project, PwBundle.message("reports.setup.error.noConfig", config.displayName))
            return
        }

        // On the EDT: PSI reads are allowed here.
        val existing = ReporterConfigEditor.readReporters(psiFile).orEmpty()
        val configured = existing.mapNotNull { ReporterKind.byConfigName(it.name) }.toSet()

        val dialog = ReportingSetupDialog(project, config.displayName, configured)
        if (!dialog.showAndGet()) return
        val kinds = dialog.selectedKinds
        val allKinds = (configured + kinds).toList()

        if (kinds.isNotEmpty()) {
            val plan = ReporterConfigEditor.plan(psiFile, kinds)
            when (plan) {
                is ReporterConfigEditor.Plan.Edit -> if (!previewAndApply(project, config, document.text, plan.newText)) return
                is ReporterConfigEditor.Plan.Manual -> showManual(project, plan)
                is ReporterConfigEditor.Plan.UpToDate -> Unit
            }
        }

        if (dialog.updateGitignore) updateGitignore(project, config.workingDir, allKinds.flatMap { it.gitignore })

        val missing = allKinds.flatMap { it.packages }.distinct().filter { !isInstalled(config, it) }
        if (missing.isNotEmpty()) {
            install(project, config, missing)
        } else {
            PwNotifications.info(project, PwBundle.message("reports.setup.done", allKinds.joinToString { it.configName }))
            project.messageBus.syncPublisher(PwTopics.REPORTS_CHANGED).reportsChanged()
        }
    }

    /** Shows the config change as a modal diff, then asks to apply it. Returns false when the user cancels. */
    private fun previewAndApply(project: Project, config: PwConfig, oldText: String, newText: String): Boolean {
        val factory = DiffContentFactory.getInstance()
        val fileType = config.configFile.fileType
        val request = SimpleDiffRequest(
            PwBundle.message("reports.setup.diff.title", config.displayName),
            factory.create(project, oldText, fileType),
            factory.create(project, newText, fileType),
            PwBundle.message("reports.setup.diff.current"),
            PwBundle.message("reports.setup.diff.after"),
        )
        DiffManager.getInstance().showDiff(project, request, DiffDialogHints.MODAL)
        val apply = MessageDialogBuilder.yesNo(
            PwBundle.message("reports.setup.apply.title"),
            PwBundle.message("reports.setup.apply.message", config.displayName),
        ).ask(project)
        if (!apply) return false

        val document = FileDocumentManager.getInstance().getDocument(config.configFile) ?: return false
        WriteCommandAction.runWriteCommandAction(project, PwBundle.message("reports.setup.command"), null, {
            replaceChangedRegion(document, newText)
        })
        PsiDocumentManager.getInstance(project).commitDocument(document)
        FileDocumentManager.getInstance().saveDocument(document)
        return true
    }

    /** Replaces only the changed middle part, so the edit is a small, undoable change. */
    private fun replaceChangedRegion(document: com.intellij.openapi.editor.Document, newText: String) {
        val old = document.charsSequence
        var prefix = 0
        val max = minOf(old.length, newText.length)
        while (prefix < max && old[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        document.replaceString(prefix, old.length - suffix, newText.substring(prefix, newText.length - suffix))
    }

    private fun showManual(project: Project, plan: ReporterConfigEditor.Plan.Manual) {
        CopyPasteManager.getInstance().setContents(StringSelection(plan.snippet))
        Messages.showInfoMessage(
            project,
            PwBundle.message("reports.setup.manual.message", plan.reason, plan.snippet),
            PwBundle.message("reports.setup.manual.title"),
        )
    }

    private fun updateGitignore(project: Project, dir: VirtualFile, lines: List<String>) {
        if (lines.isEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, PwBundle.message("reports.setup.command.gitignore"), null, {
            val file = dir.findChild(".gitignore") ?: dir.createChildData(ReportingSetup, ".gitignore")
            val text = VfsUtil.loadText(file)
            val present = text.lines().map { it.trim() }.toSet()
            val missing = lines.filter { it !in present && it.removePrefix("/") !in present && it.removePrefix("/").removeSuffix("/") !in present }
            if (missing.isEmpty()) return@runWriteCommandAction
            val newText = buildString {
                append(text)
                if (text.isNotEmpty() && !text.endsWith("\n")) append("\n")
                if (text.isNotEmpty()) append("\n")
                append("# Playwright Toolkit reporting\n")
                missing.forEach { append(it).append("\n") }
            }
            VfsUtil.saveText(file, newText)
        })
    }

    private fun isInstalled(config: PwConfig, pkg: String): Boolean {
        var dir: VirtualFile? = config.workingDir
        while (dir != null) {
            if (dir.findFileByRelativePath("node_modules/$pkg/package.json") != null) return true
            dir = dir.parent
        }
        return false
    }

    private fun install(project: Project, config: PwConfig, packages: List<String>) {
        val pm = PackageManager.detect(config.workingDir)
        val cmd = try {
            pm.addDevCommand(project, config.workingDir, packages)
        } catch (e: PwException) {
            PwNotifications.error(project, PwBundle.message("reports.setup.error.install", e.message ?: "", pm.commandText(packages)))
            return
        }
        val runner = NodeRunner.getInstance(project)
        val console = runner.createConsole()
        val handler = runner.start(cmd, console, object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().invokeLater({
                    VfsUtil.markDirtyAndRefresh(true, true, false, config.workingDir)
                    if (event.exitCode == 0) {
                        PwNotifications.info(project, PwBundle.message("reports.setup.installed", packages.joinToString(" ")))
                    } else {
                        PwNotifications.error(project, PwBundle.message("reports.setup.error.installExit", event.exitCode, pm.commandText(packages)))
                    }
                    project.messageBus.syncPublisher(PwTopics.REPORTS_CHANGED).reportsChanged()
                }, { project.isDisposed })
            }
        })
        val panel = JPanel(BorderLayout()).apply { add(console.component, BorderLayout.CENTER) }
        val descriptor = RunContentDescriptor(console, handler, panel, PwBundle.message("reports.setup.install.tab", pm.binary))
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
    }
}
