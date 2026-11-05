/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import javax.swing.JComponent

/** Tool window title combo that picks the active Playwright config. */
class ConfigPickerAction : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selected = project?.let { PlaywrightProjectLocator.getInstance(it).selectedConfig() }
        e.presentation.text = selected?.let { label(it) } ?: PwBundle.message("toolwindow.config.none")
        e.presentation.isEnabled = project != null && PlaywrightProjectLocator.getInstance(project).configs().isNotEmpty()
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        val project = dataContext.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT) ?: return group
        val locator = PlaywrightProjectLocator.getInstance(project)
        for (config in locator.configs()) {
            group.add(object : DumbAwareAction(label(config)) {
                override fun actionPerformed(e: AnActionEvent) = locator.select(config)
            })
        }
        return group
    }

    private fun label(config: PwConfig): String {
        val version = config.version?.raw ?: "?"
        val suffix = if (config.isSupported) "" else " " + PwBundle.message("toolwindow.config.unsupported")
        return "${config.displayName} (Playwright $version)$suffix"
    }
}
