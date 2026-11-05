/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.ui.webview.LocatorsWebView
import dev.uzi.pw.ui.webview.SettingsWebView
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * The main Playwright tab, arranged like playwright-vscode's Testing sidebar: the test tree, then the PLAYWRIGHT
 * view and the LOCATORS view. The two views are playwright-vscode's own webview pages (HTML/CSS) rendered in JCEF;
 * without JCEF, Swing versions with the same sections are used.
 */
class PlaywrightPanel(project: Project, parentDisposable: Disposable) {

    val component: JComponent

    init {
        val tree = TestTreePanel(project, parentDisposable)
        val lower: JComponent = if (JBCefApp.isSupported()) {
            val settings = SettingsWebView(project, parentDisposable)
            val locators = LocatorsWebView(project, parentDisposable)
            OnePixelSplitter(true, "Playwright.Panel.Views", 0.62f).apply {
                firstComponent = view(PwBundle.message("view.playwright"), settings.component)
                secondComponent = view(PwBundle.message("view.locators"), locators.component)
            }
        } else {
            val sidebar = PlaywrightSidebar(project, parentDisposable)
            val locators = LocatorsSection(project, parentDisposable)
            val stack = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(sidebar.component)
                add(locators.component)
            }
            JBScrollPane(stack).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = null
            }
        }
        component = OnePixelSplitter(true, "Playwright.Panel.Splitter", 0.42f).apply {
            firstComponent = view(PwBundle.message("view.tests"), tree.component)
            secondComponent = lower
        }
    }

    /** A VS Code-style view: a small bold uppercase title bar above the content. */
    private fun view(title: String, content: JComponent): JComponent = JPanel(BorderLayout()).apply {
        val header = JBLabel(title).apply {
            font = JBFont.small().asBold()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(3, 8),
            )
        }
        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }
}
