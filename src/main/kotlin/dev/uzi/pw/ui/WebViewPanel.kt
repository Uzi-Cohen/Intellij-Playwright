/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import dev.uzi.pw.PwBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Shows a URL in JCEF, or a link to the system browser when JCEF is not available. Call [show] on the EDT. */
class WebViewPanel(parentDisposable: Disposable, private val emptyText: String) {

    private val root = JPanel(BorderLayout())
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private var current: String? = null

    val component: JComponent get() = root
    val url: String? get() = current

    init {
        browser?.let { Disposer.register(parentDisposable, it) }
        show(null)
    }

    fun show(url: String?) {
        val changed = url != current
        current = url
        root.removeAll()
        when {
            url == null -> root.add(JBLabel(emptyText, SwingConstants.CENTER), BorderLayout.CENTER)
            browser != null -> {
                root.add(browser.component, BorderLayout.CENTER)
                if (changed) browser.loadURL(url) else browser.cefBrowser.reload()
            }
            else -> {
                BrowserUtil.browse(url)
                val panel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    border = JBUI.Borders.empty(8)
                    add(JBLabel(PwBundle.message("web.noJcef")))
                    add(ActionLink(PwBundle.message("web.openInBrowser")) { BrowserUtil.browse(url) })
                }
                root.add(panel, BorderLayout.NORTH)
            }
        }
        root.revalidate()
        root.repaint()
    }

    fun openInSystemBrowser() {
        current?.let { BrowserUtil.browse(it) }
    }
}
