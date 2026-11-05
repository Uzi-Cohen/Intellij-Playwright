/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.recorder

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PwConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Opens a page in the debug controller's browser by running a test connected to it, the way
 * playwright-vscode's `_showBrowserForRecording` does: `PW_TEST_CONNECT_WS_ENDPOINT` plus `PW_TEST_REUSE_CONTEXT=1`
 * (both read in playwright `packages/playwright/src/index.ts`). The test leaves its page open.
 */
object BootstrapRunner {
    private val LOG = logger<BootstrapRunner>()
    private const val TIMEOUT_MS = 120_000

    private fun env(wsEndpoint: String) = mapOf(
        "PW_TEST_CONNECT_WS_ENDPOINT" to wsEndpoint,
        "PW_TEST_REUSE_CONTEXT" to "1",
    )

    /** Runs the freshly created recorded test [testFile] with the user's config and [projectName]. */
    suspend fun runRecordedFile(project: Project, config: PwConfig, projectName: String, testFile: Path, wsEndpoint: String): Boolean {
        val relative = try {
            Path.of(config.workingDir.path).relativize(testFile).toString().replace('\\', '/')
        } catch (e: IllegalArgumentException) {
            testFile.toString().replace('\\', '/')
        }
        val args = mutableListOf("test", escapeRegex(relative), "-c", config.configFile.path, "--headed", "--workers", "1", "--reporter", "line")
        if (projectName.isNotEmpty()) args += listOf("--project", projectName)
        return run(project, config, args, wsEndpoint)
    }

    /** Runs a throwaway config + spec from a temp dir that only does `page.goto(url)`. Does not use the user's config. */
    suspend fun openUrl(project: Project, config: PwConfig, url: String, wsEndpoint: String): Boolean {
        val packageDir = config.packageDir ?: return false
        val dir = withContext(Dispatchers.IO) {
            val d = Path.of(PathManager.getTempPath(), "pw-intellij", UUID.randomUUID().toString())
            Files.createDirectories(d)
            Files.writeString(
                d.resolve("pw-intellij-bootstrap.config.cjs"),
                """
                module.exports = {
                  testDir: __dirname,
                  testMatch: 'pw-intellij-bootstrap.spec.cjs',
                  reporter: 'line',
                  workers: 1,
                  use: { headless: false },
                };
                """.trimIndent() + "\n",
            )
            Files.writeString(
                d.resolve("pw-intellij-bootstrap.spec.cjs"),
                "const { test } = require(${NewTestFile.jsString(packageDir.toString())});\n" +
                    "test('open', async ({ page }) => { await page.goto(${NewTestFile.jsString(url)}); });\n",
            )
            d
        }
        return try {
            run(project, config, listOf("test", "-c", dir.resolve("pw-intellij-bootstrap.config.cjs").toString(), "--reporter", "line"), wsEndpoint)
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
    }

    private suspend fun run(project: Project, config: PwConfig, args: List<String>, wsEndpoint: String): Boolean {
        val runner = NodeRunner.getInstance(project)
        val cmd = runner.commandLine(config, args, env(wsEndpoint))
        val output = runner.runCapture(cmd, TIMEOUT_MS)
        if (output.exitCode != 0 || output.isTimeout) {
            LOG.warn("Bootstrap run failed (exit=${output.exitCode}, timeout=${output.isTimeout}): ${output.stdout.takeLast(2000)} ${output.stderr.takeLast(2000)}")
            return false
        }
        return true
    }

    /** Escapes a path for Playwright's file filter, which is a JS regular expression. */
    fun escapeRegex(s: String): String = s.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\]""")) { "\\" + it.value }
}
