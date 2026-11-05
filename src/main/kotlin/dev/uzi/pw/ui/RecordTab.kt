/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import javax.swing.JComponent

/** Record tab (shell; the recorder and locator picker fill it in step 4). */
class RecordTab(private val project: Project, private val parentDisposable: Disposable) {
    val component: JComponent = SimpleToolWindowPanel(true, true).apply {
        setContent(JBLabel(PwBundle.message("toolwindow.record.placeholder")).apply { border = JBUI.Borders.empty(8) })
    }
}
