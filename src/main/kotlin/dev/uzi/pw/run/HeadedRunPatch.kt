/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.run

import com.intellij.openapi.project.Project
import dev.uzi.pw.settings.PwSettings

/** "Show browser": adds `--headed` (playwright-vscode passes `headed: true` when its show-browser setting is on). */
object HeadedRunPatch : RunPatchContributor {

    override fun isActive(project: Project): Boolean = PwSettings.getInstance(project).state.showBrowser

    override suspend fun patch(context: RunPatchContext): RunPatch? {
        if (context.userHasOption("--headed")) return null
        return RunPatch(cliArgs = listOf("--headed"))
    }
}
