/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.trace

import com.intellij.openapi.project.Project
import dev.uzi.pw.run.RunPatch
import dev.uzi.pw.run.RunPatchContext
import dev.uzi.pw.run.RunPatchContributor
import dev.uzi.pw.settings.PwSettings

/**
 * "Always record trace": adds `--trace on` unless the run configuration already sets `--trace`.
 * Inactive while "reuse browser" is in effect (the settings keep the two exclusive, as playwright-vscode does).
 */
object TraceRunPatch : RunPatchContributor {

    override fun isActive(project: Project): Boolean {
        val settings = PwSettings.getInstance(project)
        return settings.state.alwaysRecordTrace && !settings.isReuseBrowserEffective
    }

    override suspend fun patch(context: RunPatchContext): RunPatch? {
        if (context.userHasOption("--trace")) return null
        return RunPatch(cliArgs = listOf("--trace", "on"))
    }
}
