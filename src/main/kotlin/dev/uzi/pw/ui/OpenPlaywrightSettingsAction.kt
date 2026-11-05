/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwConfigurable

/** Opens Settings | Tools | Playwright. */
class OpenPlaywrightSettingsAction : DumbAwareAction(
    PwBundle.message("toolwindow.openSettings"),
    null,
    AllIcons.General.Settings,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PwConfigurable::class.java)
    }
}
