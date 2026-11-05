/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.PwActionBase
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.settings.PwSettings
import javax.swing.JComponent

/** Asks for a seed URL and a description, then runs the planner agent. New `specs/` Markdown files are opened. */
object Planner {
    /** Shows the dialog and starts the run. Call on the EDT. */
    fun showAndRun(project: Project, config: PwConfig) {
        val dialog = PlanDialog(project, PwSettings.getInstance(project).state.defaultRecordUrl.orEmpty())
        if (!dialog.showAndGet()) return
        val description = dialog.description.trim().trimEnd('.')
        val url = dialog.seedUrl.trim()
        val task = if (url.isEmpty()) description else "$description. Seed URL: $url"
        AgentService.getInstance(project).runAgent(AgentKind.PLANNER, task, config)
    }
}

class PlanDialog(project: Project, initialUrl: String) : DialogWrapper(project) {
    private val urlField = JBTextField(initialUrl)
    private val descriptionArea = JBTextArea(6, 50).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    val seedUrl: String get() = urlField.text
    val description: String get() = descriptionArea.text

    init {
        title = PwBundle.message("agents.plan.dialog.title")
        setOKButtonText(PwBundle.message("agents.plan.dialog.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(PwBundle.message("agents.plan.dialog.url"), urlField)
            .addLabeledComponent(PwBundle.message("agents.plan.dialog.description"), JBScrollPane(descriptionArea), true)
            .panel
            .apply { preferredSize = JBUI.size(520, 240) }

    override fun getPreferredFocusedComponent(): JComponent = if (urlField.text.isBlank()) urlField else descriptionArea

    override fun doValidate(): ValidationInfo? =
        if (descriptionArea.text.isBlank()) ValidationInfo(PwBundle.message("agents.plan.dialog.descriptionRequired"), descriptionArea) else null
}

/** Tools menu: plan tests with the planner agent. */
class PlanAction : PwActionBase() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        if (AgentService.getInstance(project).isRunning) e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = config(e) ?: return
        Planner.showAndRun(project, config)
    }
}
