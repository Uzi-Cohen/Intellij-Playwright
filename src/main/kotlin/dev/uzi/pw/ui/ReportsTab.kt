/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwReportsListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.reports.ReportingSetup
import dev.uzi.pw.reports.ReportsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Reports tab: set up reporting, and view the HTML and Allure reports of the selected config. */
class ReportsTab(private val project: Project, parentDisposable: Disposable) {

    private val service = ReportsService.getInstance(project)
    private val status = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    private val web = WebViewPanel(parentDisposable, PwBundle.message("reports.empty"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var locations: ReportsService.Locations? = null

    val component: JComponent

    init {
        val group = DefaultActionGroup().apply {
            add(TabAction(PwBundle.message("reports.action.setup"), AllIcons.General.Settings, { configSupported() }) {
                PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported }?.let { ReportingSetup.run(project, it) }
            })
            addSeparator()
            add(TabAction(PwBundle.message("reports.action.html"), AllIcons.FileTypes.Html, { locations?.htmlExists == true }) {
                service.showHtml()
            })
            add(TabAction(PwBundle.message("reports.action.allure"), AllIcons.Actions.Preview, {
                locations?.let { it.allureCli != null && it.allureResultsExist } == true
            }) { service.showAllure() })
            add(TabAction(PwBundle.message("reports.action.reload"), AllIcons.Actions.Refresh, { service.shown != null }) {
                service.reload()
            })
            add(TabAction(PwBundle.message("reports.action.openInBrowser"), AllIcons.General.Web, { web.url != null }) {
                web.openInSystemBrowser()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("PlaywrightReportsTab", group, true)
        val body = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(web.component, BorderLayout.CENTER)
        }
        component = SimpleToolWindowPanel(true, true).apply {
            toolbar.targetComponent = this
            setToolbar(toolbar.component)
            setContent(body)
        }

        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(PwTopics.REPORTS_CHANGED, PwReportsListener { refresh() })
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener { refresh() })
        com.intellij.openapi.util.Disposer.register(parentDisposable) { scope.cancel() }
        refresh()
    }

    private fun configSupported(): Boolean =
        PlaywrightProjectLocator.getInstance(project).selectedConfig()?.isSupported == true

    private fun refresh() {
        scope.launch {
            val loc = service.locations()
            locations = loc
            val text = when {
                loc == null -> PwBundle.message("reports.status.noConfig")
                else -> listOf(
                    when {
                        !loc.htmlConfigured -> PwBundle.message("reports.status.html.off")
                        loc.htmlExists -> PwBundle.message("reports.status.html.ready")
                        else -> PwBundle.message("reports.status.html.none")
                    },
                    when {
                        !loc.allureConfigured -> PwBundle.message("reports.status.allure.off")
                        loc.allureCli == null -> PwBundle.message("reports.status.allure.noCli")
                        loc.allureResultsExist -> PwBundle.message("reports.status.allure.ready")
                        else -> PwBundle.message("reports.status.allure.none")
                    },
                ).joinToString("   ")
            }
            val url = service.shown?.url
            withContext(Dispatchers.EDT) {
                status.text = text
                if (url != web.url || url != null) web.show(url)
            }
        }
    }

    private class TabAction(
        text: String,
        icon: javax.swing.Icon,
        private val enabled: () -> Boolean,
        private val perform: () -> Unit,
    ) : DumbAwareAction(text, text, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
