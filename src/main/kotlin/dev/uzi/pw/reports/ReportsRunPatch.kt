/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.uzi.pw.run.RunPatch
import dev.uzi.pw.run.RunPatchContext
import dev.uzi.pw.run.RunPatchContributor
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * "Clear Allure results before each run": allure-playwright appends to its results folder, so without this every
 * run since setup would end up in one report. Adds nothing to the command line.
 */
object ReportsRunPatch : RunPatchContributor {
    private val LOG = logger<ReportsRunPatch>()

    override fun isActive(project: Project): Boolean = PwSettings.getInstance(project).state.cleanAllureResults

    override suspend fun patch(context: RunPatchContext): RunPatch? {
        val loc = ReportsService.getInstance(context.project).locations() ?: return null
        if (!loc.allureConfigured) return null
        withContext(Dispatchers.IO) {
            val dir = loc.allureResultsDir
            if (Files.isDirectory(dir)) {
                runCatching { dir.toFile().deleteRecursively() }.onFailure { LOG.info("Cannot clear $dir: ${it.message}") }
            }
        }
        return null
    }
}
