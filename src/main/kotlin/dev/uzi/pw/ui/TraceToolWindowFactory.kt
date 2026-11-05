/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.uzi.pw.trace.TraceViewerPanel

/**
 * "Playwright Trace": the trace viewer in its own bottom tool window, so "Show trace viewer" can open a test's trace
 * without leaving the test tree (playwright-vscode opens the trace viewer in a separate window).
 */
class TraceToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean =
        PlaywrightToolWindowFactory().isApplicableAsync(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable = Disposer.newDisposable("Playwright Trace")
        val panel = TraceViewerPanel(project, disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(disposable)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
