/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.project.DumbAware
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwSettings

/** Tool window toolbar checkbox for the "Show browser" setting. */
class ShowBrowserToggle : CheckboxAction(PwBundle.message("toggle.showBrowser"), PwBundle.message("toggle.showBrowser.description"), null), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { PwSettings.getInstance(it).state.showBrowser } ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        PwSettings.getInstance(project).setShowBrowser(state)
    }
}
