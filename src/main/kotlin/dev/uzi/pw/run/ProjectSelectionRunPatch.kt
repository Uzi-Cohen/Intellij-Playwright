/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.run

import com.intellij.openapi.project.Project
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.settings.PwSettings

/**
 * PROJECTS checkboxes: when some projects of the config are unchecked, runs get `--project <name>` for each checked
 * one, like playwright-vscode, which only runs the enabled projects of the selected config. Nothing is added when
 * all are checked, or when the run configuration already sets `--project`.
 */
object ProjectSelectionRunPatch : RunPatchContributor {

    override fun isActive(project: Project): Boolean {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig() ?: return false
        return PwSettings.getInstance(project).disabledProjects(config.configFile.path).isNotEmpty()
    }

    override suspend fun patch(context: RunPatchContext): RunPatch? {
        if (context.userHasOption("--project")) return null
        val config = context.pwConfig ?: return null
        val disabled = PwSettings.getInstance(context.project).disabledProjects(config.configFile.path)
        if (disabled.isEmpty()) return null
        val projects = PlaywrightProjectLocator.getInstance(context.project).projects(config)
        val enabled = projects.map { it.name }.filter { it !in disabled }
        if (enabled.isEmpty() || enabled.size == projects.size) return null
        return RunPatch(cliArgs = enabled.flatMap { listOf("--project", it) })
    }
}
