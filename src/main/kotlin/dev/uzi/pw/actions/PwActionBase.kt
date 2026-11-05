/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig

/** Base for actions that need a supported Playwright config; disabled when there is none. */
abstract class PwActionBase : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** The selected config when it is supported, else null. */
    protected fun config(e: AnActionEvent): PwConfig? {
        val project = e.project ?: return null
        return PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        e.presentation.isEnabled = config(e) != null
    }
}
