/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.ui.webview

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Color
import javax.swing.JComponent

/**
 * Hosts one of playwright-vscode's webview pages (resources/webview/<page>.html + .js, with media/common.css) in JCEF.
 *
 * - `vscode.postMessage(msg)` in the page goes to [onMessage] through a [JBCefJSQuery] (`window.__pwSend`).
 * - [post] delivers `window.postMessage({ method, params })` to the page, as the extension's `webview.postMessage`
 *   does. Messages posted before the page says `ready` are kept (the latest per method) and sent then.
 * - The `--vscode-*` colour variables common.css uses are defined from the IDE theme, and the page is reloaded
 *   when the theme changes.
 */
class PwWebView(
    parentDisposable: Disposable,
    private val page: String,
    private val onMessage: (method: String, params: JsonObject) -> Unit,
    private val onReady: () -> Unit,
) {
    private val browser = JBCefBrowser.createBuilder().setEnableOpenDevToolsMenuItem(false).build()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val pending = LinkedHashMap<String, JsonObject>()
    private var ready = false

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parentDisposable, browser)
        Disposer.register(parentDisposable, query)
        query.addHandler { raw ->
            try {
                val msg = Json.parseToJsonElement(raw).jsonObject
                val method = msg["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
                ApplicationManager.getApplication().invokeLater {
                    if (method == "ready") markReady() else onMessage(method, params)
                }
            } catch (e: Exception) {
                LOG.warn("Bad message from $page: $raw", e)
            }
            null
        }
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { load() })
        load()
    }

    /** Sends `{ method, params }` to the page. Call on the EDT. */
    fun post(method: String, params: JsonObject) {
        if (!ready) {
            pending.remove(method)
            pending[method] = params
            return
        }
        val message = buildJsonObject {
            put("method", method)
            put("params", params)
        }
        browser.cefBrowser.executeJavaScript("window.postMessage($message, '*');", browser.cefBrowser.url, 0)
    }

    private fun markReady() {
        ready = true
        val queued = pending.toList()
        pending.clear()
        queued.forEach { (m, p) -> post(m, p) }
        onReady()
    }

    private fun load() {
        ready = false
        val dark = !JBColor.isBright()
        val body = resource("$page.html").replaceFirst("<body ", "<body data-vscode-theme-kind=\"${if (dark) "vscode-dark" else "vscode-light"}\" ")
        val html = buildString {
            append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            append("<style>").append(themeCss(dark)).append("</style>")
            append("<style>").append(resource("common.css")).append("</style>")
            append("<script>window.__pwSend = function(msg) {").append(query.inject("msg")).append("};</script>")
            append("<script>").append(resource("common.js")).append("</script>")
            append("<title>Playwright</title></head>")
            append(body)
            append("<script>").append(resource("$page.js")).append("</script>")
            append("</html>")
        }
        browser.loadHTML(html)
    }

    private fun resource(name: String): String =
        PwWebView::class.java.getResourceAsStream("/webview/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing resource webview/$name")

    companion object {
        private val LOG = logger<PwWebView>()

        private fun css(c: Color): String = "rgba(${c.red}, ${c.green}, ${c.blue}, ${"%.3f".format(java.util.Locale.ROOT, c.alpha / 255.0)})"

        /** The VS Code theme variables common.css reads, taken from the IDE theme. */
        private fun themeCss(dark: Boolean): String {
            val background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
            val foreground = UIUtil.getLabelForeground()
            val inputBackground = JBColor.namedColor("TextField.background", UIUtil.getTextFieldBackground())
            val inputForeground = JBColor.namedColor("TextField.foreground", UIUtil.getTextFieldForeground())
            val border = JBColor.namedColor("Component.borderColor", JBColor.border())
            val header = UIUtil.getContextHelpForeground()
            val error = JBColor.namedColor("Label.errorForeground", JBColor.RED)
            val hover = JBColor.namedColor("List.hoverBackground", JBUI.CurrentTheme.ActionButton.hoverBackground())
            val font = UIUtil.getLabelFont()
            return """
                :root {
                  color-scheme: ${if (dark) "dark" else "light"};
                  --vscode-editor-foreground: ${css(foreground)};
                  --vscode-sideBar-background: ${css(background)};
                  --vscode-input-background: ${css(inputBackground)};
                  --vscode-input-foreground: ${css(inputForeground)};
                  --vscode-panelInput-border: ${css(border)};
                  --vscode-focusBorder: ${css(JBUI.CurrentTheme.Focus.focusColor())};
                  --vscode-editor-inlineValuesForeground: ${css(header)};
                  --vscode-toolbar-hoverBackground: ${css(JBUI.CurrentTheme.ActionButton.hoverBackground())};
                  --vscode-errorForeground: ${css(error)};
                }
                body {
                  background: ${css(background)};
                  color: ${css(foreground)};
                  font-family: '${font.family}', system-ui, -apple-system, 'Segoe UI', sans-serif;
                  font-size: 13px;
                }
                /* IDE hover colour instead of VS Code's fixed ones, so rows match the IDE's lists. */
                .action:hover, .combobox:hover,
                body[data-vscode-theme-kind=vscode-dark] .action:hover,
                body[data-vscode-theme-kind=vscode-dark] .combobox:hover { background-color: ${css(hover)}; }
                textarea { color: ${css(inputForeground)}; }
            """.trimIndent()
        }
    }
}
