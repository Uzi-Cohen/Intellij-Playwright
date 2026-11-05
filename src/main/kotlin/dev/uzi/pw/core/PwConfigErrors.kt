/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwConfigurable

/**
 * Recognises "the Playwright config failed to load" in a command's output (the config is loaded by every
 * Playwright command: `loadConfigFromFile` / `loadUserConfig` in packages/playwright/src/common/configLoader.ts)
 * and tells the user how to fix the most common cause, missing environment variables.
 */
object PwConfigErrors {
    private val CONFIG_LOAD = listOf("loadConfigFromFile", "loadUserConfig", "loadConfig (")
    private val FIRST_ERROR = Regex("""(?m)^\s*(?:Error|[A-Z][A-Za-z]*Error): (.+)$""")
    private val EXAMPLE_NAMES = listOf(".env.example", ".env.sample", ".env.template")

    /** True when [output] looks like a config load failure. */
    fun isConfigLoadFailure(output: String): Boolean = CONFIG_LOAD.any { output.contains(it) }

    /**
     * Shows a notification with the first error line and a link to the plugin's environment settings. When the
     * project has a `.env.example` (or `.sample` / `.template`) but no `.env`, it also offers to create `.env` from it.
     */
    fun notifyIfConfigLoadFailed(project: Project, config: PwConfig?, output: String): Boolean {
        if (!isConfigLoadFailure(output)) return false
        val message = FIRST_ERROR.find(output)?.groupValues?.get(1)?.trim()?.take(300) ?: output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        val actions = mutableListOf<AnAction>()
        val dir = config?.workingDir
        val example = dir?.let { d -> EXAMPLE_NAMES.firstNotNullOfOrNull { d.findChild(it) } }
        if (dir != null && example != null && dir.findChild(".env") == null) {
            actions += NotificationAction.createSimpleExpiring(PwBundle.message("config.error.createEnv", example.name)) {
                createEnvFromExample(project, dir, example)
            }
        }
        actions += NotificationAction.createSimpleExpiring(PwBundle.message("config.error.openSettings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PwConfigurable::class.java)
        }
        PwNotifications.error(
            project,
            PwBundle.message("config.error.load", config?.displayName ?: "playwright.config", message),
            *actions.toTypedArray(),
        )
        return true
    }

    /** Copies [example] to `.env` next to it and opens it, so the user can fill in the values. Call on the EDT. */
    private fun createEnvFromExample(project: Project, dir: VirtualFile, example: VirtualFile) {
        val created = WriteCommandAction.writeCommandAction(project)
            .withName(PwBundle.message("config.error.createEnv.command"))
            .compute<VirtualFile, java.io.IOException> {
                dir.findChild(".env") ?: dir.createChildData(PwConfigErrors, ".env").also { VfsUtil.saveText(it, VfsUtil.loadText(example)) }
            }
        FileEditorManager.getInstance(project).openFile(created, true)
        PwNotifications.info(project, PwBundle.message("config.error.createEnv.done"))
    }
}
