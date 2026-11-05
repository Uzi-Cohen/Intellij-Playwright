/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.run

import com.intellij.openapi.project.Project
import dev.uzi.pw.settings.PwSettings

/**
 * SETTINGS "Update snapshots" and "Update method", as in playwright-vscode (settingsModel.ts `updateSnapshots`,
 * `updateSourceMethod`; "no-override" passes nothing). CLI options from playwright program.ts:
 * `-u, --update-snapshots [mode]` (all | changed | missing | none) and `--update-source-method <method>`
 * (overwrite | 3way | patch), both present in 1.56.
 */
object UpdateSnapshotsRunPatch : RunPatchContributor {
    const val NO_OVERRIDE = "no-override"
    val SNAPSHOT_MODES = listOf(NO_OVERRIDE, "all", "changed", "missing", "none")
    val SOURCE_METHODS = listOf(NO_OVERRIDE, "overwrite", "patch", "3way")

    override fun isActive(project: Project): Boolean {
        val state = PwSettings.getInstance(project).state
        return (state.updateSnapshots ?: NO_OVERRIDE) != NO_OVERRIDE || (state.updateSourceMethod ?: NO_OVERRIDE) != NO_OVERRIDE
    }

    override suspend fun patch(context: RunPatchContext): RunPatch? {
        val state = PwSettings.getInstance(context.project).state
        val args = mutableListOf<String>()
        val snapshots = state.updateSnapshots ?: NO_OVERRIDE
        if (snapshots != NO_OVERRIDE && !context.userHasOption("-u", "--update-snapshots")) args += "--update-snapshots=$snapshots"
        val method = state.updateSourceMethod ?: NO_OVERRIDE
        if (method != NO_OVERRIDE && !context.userHasOption("--update-source-method")) args += listOf("--update-source-method", method)
        return if (args.isEmpty()) null else RunPatch(cliArgs = args)
    }
}
