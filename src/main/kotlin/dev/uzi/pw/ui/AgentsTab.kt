/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import dev.uzi.pw.agents.AgentBackends
import dev.uzi.pw.agents.AgentService
import dev.uzi.pw.agents.InitAgents
import dev.uzi.pw.agents.Planner
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Agents tab: the chosen backend with its availability, Initialize / Plan / Heal / Stop, and the agents console.
 * [AgentService] owns the console; this tab only shows it.
 */
class AgentsTab(private val project: Project, private val parentDisposable: Disposable) {
    private val service = AgentService.getInstance(project)
    private val backendLabel = JBLabel()
    private val initButton = JButton(PwBundle.message("agents.tab.initialize"))
    private val planButton = JButton(PwBundle.message("agents.tab.plan"))
    private val healButton = JButton(PwBundle.message("agents.tab.heal"))
    private val stopButton = JButton(PwBundle.message("agents.tab.stop"), AllIcons.Actions.Suspend)

    @Volatile
    private var availabilityJob: Job? = null

    @Volatile
    private var disposed = false

    val component: JComponent

    init {
        initButton.addActionListener { selectedConfig()?.let { InitAgents.run(project, it) } }
        planButton.addActionListener { selectedConfig()?.let { Planner.showAndRun(project, it) } }
        healButton.toolTipText = PwBundle.message("agents.tab.healHint")
        healButton.addActionListener { showHealHint() }
        stopButton.addActionListener { service.stop() }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(initButton)
            add(planButton)
            add(healButton)
            add(stopButton)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(backendLabel, BorderLayout.NORTH)
            add(buttons, BorderLayout.SOUTH)
        }
        component = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(service.console.component, BorderLayout.CENTER)
        }

        Disposer.register(parentDisposable) {
            disposed = true
            availabilityJob?.cancel()
        }
        service.addStateListener({ running ->
            updateButtons(running)
            if (!running) refreshAvailability()
        }, parentDisposable)
        project.messageBus.connect(parentDisposable).subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener {
            refreshAvailability()
        })
        component.addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && component.isShowing) refreshAvailability()
        }

        updateButtons(service.isRunning)
        refreshAvailability()
    }

    private fun selectedConfig(): PwConfig? {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()
        if (config == null) {
            PwNotifications.warn(project, PwBundle.message("agents.unavailable.noConfig"))
            return null
        }
        if (!config.isSupported) {
            PwNotifications.warn(project, PwBundle.message("agents.unavailable.unsupported", config.displayName))
            return null
        }
        return config
    }

    private fun updateButtons(running: Boolean) {
        initButton.isEnabled = !running
        planButton.isEnabled = !running
        healButton.isEnabled = !running
        stopButton.isEnabled = running
    }

    private fun refreshAvailability() {
        if (disposed) return
        availabilityJob?.cancel()
        availabilityJob = service.scope.launch {
            val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()
            val backend = AgentBackends.selected(project)
            val availability = withContext(Dispatchers.IO) { AgentBackends.availability(project, config) }
            val name = backend?.displayName ?: PwBundle.message("agents.backend.mcpDirect")
            withContext(Dispatchers.EDT) {
                if (disposed) return@withContext
                if (availability.ok) {
                    backendLabel.icon = AllIcons.General.InspectionsOK
                    backendLabel.text = PwBundle.message("agents.tab.backendReady", name)
                    backendLabel.toolTipText = null
                } else {
                    backendLabel.icon = AllIcons.General.Warning
                    backendLabel.text = PwBundle.message("agents.tab.backendUnavailable", name, availability.reason ?: "")
                    backendLabel.toolTipText = availability.reason
                }
            }
        }
    }

    private fun showHealHint() {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(PwBundle.message("agents.tab.healHint"), MessageType.INFO, null)
            .setFadeoutTime(6_000)
            .createBalloon()
            .show(RelativePoint.getSouthOf(healButton), Balloon.Position.below)
    }
}
