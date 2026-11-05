/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwSettings

/**
 * "Show browser" as a checkable item in the Playwright tool window's options (⋮) menu. Kept out of the title bar
 * so the content tabs have room.
 */
class ShowBrowserToggle : DumbAwareToggleAction(
    PwBundle.message("toggle.showBrowser"),
    PwBundle.message("toggle.showBrowser.description"),
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { PwSettings.getInstance(it).state.showBrowser } ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        PwSettings.getInstance(project).setShowBrowser(state)
    }
}

/** "Reuse browser" in the options (⋮) menu; enabled only with Show browser. */
class ReuseBrowserToggle : DumbAwareToggleAction(
    PwBundle.message("toggle.reuseBrowser"),
    PwBundle.message("toggle.reuseBrowser.description"),
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.project?.let { PwSettings.getInstance(it).state.showBrowser } ?: false
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { PwSettings.getInstance(it).state.reuseBrowser } ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        PwSettings.getInstance(project).setReuseBrowser(state)
    }
}
