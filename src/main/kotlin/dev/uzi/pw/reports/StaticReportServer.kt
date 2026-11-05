/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.openapi.diagnostic.logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Serves a static report folder on `127.0.0.1:<free port>`, for Playwright's HTML report.
 *
 * Playwright 1.56's `show-report` always opens the system browser (packages/playwright/src/reporters/html.ts
 * `showHTMLReport`), so the plugin serves the folder itself and shows it in the IDE. The report is a static
 * `index.html` plus `data/` and `trace/`; its embedded trace viewer needs http(s), not `file://`.
 */
class StaticReportServer(private val root: Path) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "Playwright report server").apply { isDaemon = true } }
    private val rootReal: Path = root.toRealPath()

    val url: String get() = "http://127.0.0.1:${server.address.port}/"

    init {
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.executor = executor
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
            exchange.sendResponseHeaders(405, -1)
            return
        }
        val rawPath = URLDecoder.decode(exchange.requestURI.rawPath ?: "/", StandardCharsets.UTF_8)
        val relative = rawPath.removePrefix("/").ifEmpty { "index.html" }
        val target = try {
            rootReal.resolve(relative).normalize()
        } catch (e: Exception) {
            exchange.sendResponseHeaders(400, -1)
            return
        }
        val file = if (Files.isDirectory(target)) target.resolve("index.html") else target
        if (!file.startsWith(rootReal) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val bytes = try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            LOG.info("Cannot read $file: ${e.message}")
            exchange.sendResponseHeaders(500, -1)
            return
        }
        exchange.responseHeaders.add("Content-Type", contentType(file.fileName.toString()))
        exchange.responseHeaders.add("Cache-Control", "no-store")
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(200, -1)
            return
        }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        private val LOG = logger<StaticReportServer>()

        private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            "txt", "log" -> "text/plain; charset=utf-8"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "ico" -> "image/x-icon"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
