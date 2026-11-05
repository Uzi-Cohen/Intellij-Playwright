/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offers "Initialize agents" when the agent files for the chosen backend are missing, or when the
 * Playwright version differs from the one the agents were last initialized with (PLAN 9.1).
 * Configs may be found after startup, so the check also runs once on the first CONFIGS_CHANGED.
 */
class AgentsStartupCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        val done = AtomicBoolean(false)
        if (check(project, done)) return
        val service = AgentService.getInstance(project)
        val connection = project.messageBus.connect(service)
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener {
            if (done.get()) return@PwConfigsListener
            service.scope.launch {
                if (check(project, done)) connection.disconnect()
            }
        })
    }

    /** Returns true once a config was found and the check is finished for this session. */
    private suspend fun check(project: Project, done: AtomicBoolean): Boolean {
        if (project.isDisposed) return true
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig() ?: return false
        if (!done.compareAndSet(false, true)) return true
        if (!config.isSupported) return true
        if (InitAgents.unsupportedReason(project, config) != null) return true

        val loop = AgentBackends.initLoop(project)
        val missing = withContext(Dispatchers.IO) { !AgentFiles.presentFor(loop, config.workingDir.toNioPath()) }
        val current = config.version?.raw
        val versionChanged = PwSettings.getInstance(project).state.lastInitAgentsVersion != current
        if (!missing && !versionChanged) return true

        val message = if (missing) PwBundle.message("agents.startup.missing", loop, config.displayName)
        else PwBundle.message("agents.startup.versionChanged", current ?: "?")
        PwNotifications.info(project, message, initAction(project, config))
        return true
    }

    private fun initAction(project: Project, config: PwConfig) =
        object : DumbAwareAction(PwBundle.message("agents.startup.initialize")) {
            override fun actionPerformed(e: AnActionEvent) {
                InitAgents.run(project, config)
            }
        }
}
