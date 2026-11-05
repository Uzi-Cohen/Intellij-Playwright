/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.trace

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.uzi.pw.PwBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shows the trace viewer served by [TraceService]: embedded in JCEF when available, otherwise in the system
 * browser with a label and a link here. An "Open in system browser" toolbar button is always present.
 */
class TraceViewerPanel(project: Project, parentDisposable: Disposable) {

    private val root = JPanel(BorderLayout())
    private val content = JPanel(BorderLayout())
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private var url: String? = null
    private var loadedUrl: String? = null
    private var browsedUrl: String? = null

    val component: JComponent get() = root

    init {
        browser?.let { Disposer.register(parentDisposable, it) }

        val group = DefaultActionGroup(OpenInBrowserAction())
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = root
        root.add(toolbar.component, BorderLayout.NORTH)
        root.add(content, BorderLayout.CENTER)

        showUrl(null)
        TraceService.getInstance(project).watchUrl(parentDisposable) { showUrl(it) }
    }

    /** Must be called on the EDT. */
    private fun showUrl(newUrl: String?) {
        url = newUrl
        content.removeAll()
        if (newUrl == null) {
            content.add(centeredLabel(PwBundle.message("trace.viewer.empty")), BorderLayout.CENTER)
        } else if (browser != null) {
            if (loadedUrl != newUrl) {
                browser.loadURL(newUrl)
                loadedUrl = newUrl
            }
            content.add(browser.component, BorderLayout.CENTER)
        } else {
            if (browsedUrl != newUrl) {
                browsedUrl = newUrl
                BrowserUtil.browse(newUrl)
            }
            content.add(externalBrowserNotice(newUrl), BorderLayout.CENTER)
        }
        content.revalidate()
        content.repaint()
    }

    private fun centeredLabel(text: String): JComponent =
        JBLabel(text, SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun externalBrowserNotice(target: String): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(16)
        panel.add(JBLabel(PwBundle.message("trace.viewer.noJcef")))
        val linkRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(8)))
        linkRow.add(ActionLink(PwBundle.message("trace.viewer.openInBrowser")) { BrowserUtil.browse(target) })
        panel.add(linkRow)
        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    private inner class OpenInBrowserAction : DumbAwareAction(
        PwBundle.message("trace.action.openInSystemBrowser"),
        null,
        AllIcons.General.Web,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = url != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            url?.let { BrowserUtil.browse(it) }
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "PlaywrightTraceViewer"
    }
}
