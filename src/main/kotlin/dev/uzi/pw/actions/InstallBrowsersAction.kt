/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.actions

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import java.awt.BorderLayout
import javax.swing.JPanel

/** Tools | Playwright | Install Browsers: runs `node <cli.js> install` and shows the output in the Run tool window. */
class InstallBrowsersAction : PwActionBase() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = config(e) ?: return
        run(project, config)
    }

    companion object {
        fun run(project: Project, config: PwConfig) {
            val runner = NodeRunner.getInstance(project)
            val cmd = try {
                runner.commandLine(config, listOf("install"))
            } catch (ex: PwException) {
                PwNotifications.error(project, ex.message ?: "")
                return
            }
            val console: ConsoleView = runner.createConsole()
            val handler = runner.start(cmd, console)
            val panel = JPanel(BorderLayout()).apply { add(console.component, BorderLayout.CENTER) }
            val descriptor = RunContentDescriptor(console, handler, panel, PwBundle.message("action.installBrowsers.tab"))
            Disposer.register(descriptor, console)
            RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        }
    }
}
