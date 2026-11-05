/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.notification.NotificationAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
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

    /** True when [output] looks like a config load failure. */
    fun isConfigLoadFailure(output: String): Boolean = CONFIG_LOAD.any { output.contains(it) }

    /** Shows a notification with the first error line and a link to the plugin's environment settings. */
    fun notifyIfConfigLoadFailed(project: Project, config: PwConfig?, output: String): Boolean {
        if (!isConfigLoadFailure(output)) return false
        val message = FIRST_ERROR.find(output)?.groupValues?.get(1)?.trim()?.take(300) ?: output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        PwNotifications.error(
            project,
            PwBundle.message("config.error.load", config?.displayName ?: "playwright.config", message),
            NotificationAction.createSimpleExpiring(PwBundle.message("config.error.openSettings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PwConfigurable::class.java)
            },
        )
        return true
    }
}
