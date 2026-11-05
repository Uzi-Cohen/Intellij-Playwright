/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * The main Playwright tab, arranged like playwright-vscode's Testing sidebar: the test tree on top, and below it
 * the PLAYWRIGHT section (TOOLS, CONFIGS, PROJECTS, SETUP, SETTINGS) followed by LOCATORS.
 */
class PlaywrightPanel(project: Project, parentDisposable: Disposable) {

    val component: JComponent

    init {
        val tree = TestTreePanel(project, parentDisposable)
        val sidebar = PlaywrightSidebar(project, parentDisposable)
        val locators = LocatorsSection(project, parentDisposable)

        val lower = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sidebar.component)
            add(locators.component)
        }
        val scroll = JBScrollPane(lower).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }
        component = OnePixelSplitter(true, "Playwright.Panel.Splitter", 0.45f).apply {
            firstComponent = tree.component
            secondComponent = scroll
        }
    }
}
