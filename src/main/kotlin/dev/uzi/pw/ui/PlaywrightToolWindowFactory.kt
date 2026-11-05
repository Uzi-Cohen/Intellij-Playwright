/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.content.ContentFactory
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator
import javax.swing.JComponent

/** The "Playwright" tool window: Record / Traces / Agents tabs and a config picker in the title. */
class PlaywrightToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean {
        if (PlaywrightProjectLocator.getInstance(project).configs().isNotEmpty()) return true
        return readAction {
            val scope = GlobalSearchScope.projectScope(project)
            PlaywrightProjectLocator.CONFIG_NAMES.any { FilenameIndex.getVirtualFilesByName(it, scope).isNotEmpty() }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Only the config picker in the title bar; anything wider pushes the content tabs out of view.
        toolWindow.setTitleActions(listOf(ConfigPickerAction()))
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(ShowBrowserToggle(), ReuseBrowserToggle(), Separator.getInstance(), OpenPlaywrightSettingsAction()),
        )
        addTab(toolWindow, PwBundle.message("toolwindow.tab.record")) { d -> RecordTab(project, d).component }
        addTab(toolWindow, PwBundle.message("toolwindow.tab.traces")) { d -> TracesTab(project, d).component }
        addTab(toolWindow, PwBundle.message("toolwindow.tab.reports")) { d -> ReportsTab(project, d).component }
        addTab(toolWindow, PwBundle.message("toolwindow.tab.agents")) { d -> AgentsTab(project, d).component }
    }

    private fun addTab(toolWindow: ToolWindow, title: String, create: (Disposable) -> JComponent) {
        val disposable = Disposer.newDisposable("Playwright tab $title")
        val content = ContentFactory.getInstance().createContent(create(disposable), title, false)
        content.setDisposer(disposable)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
