/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.run.PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds and serves the reports of the selected Playwright config:
 * - HTML: the html reporter's `outputFolder` (default `playwright-report` next to the config), served by
 *   [StaticReportServer].
 * - Allure 3: `node <allure>/cli.js serve --host 127.0.0.1 <resultsDir>` (generates the report on the fly and serves
 *   it without opening a browser; prints `Allure is running on <url>.`, see @allurereport/static-server).
 * After every Playwright run it reloads whatever is shown, and opens it when "Open report after run" is on.
 */
@Service(Service.Level.PROJECT)
class ReportsService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    enum class Kind { HTML, ALLURE }

    /** What the Reports tab shows. */
    data class Shown(val kind: Kind, val url: String)

    /** Where the selected config's reports are, read from the config's reporter option. */
    data class Locations(
        val config: PwConfig,
        val htmlConfigured: Boolean,
        val htmlDir: Path,
        val allureConfigured: Boolean,
        val allureResultsDir: Path,
        val allureCli: Path?,
        /** Checked on disk when the locations were read (off the EDT). */
        val htmlExists: Boolean,
        val allureResultsExist: Boolean,
    )

    @Volatile
    var shown: Shown? = null
        private set

    private val mutex = Mutex()
    private var htmlServer: StaticReportServer? = null
    private var allureHandler: KillableColoredProcessHandler? = null

    init {
        project.messageBus.connect(cs).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                val profile = env.runProfile
                if (profile is RunConfiguration && profile.type.id == PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID) onRunFinished()
            }
        })
    }

    /** Report locations for the selected config, or null when there is no supported config. */
    suspend fun locations(): Locations? {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported } ?: return null
        val reporters = readAction {
            PsiManager.getInstance(project).findFile(config.configFile)?.let { ReporterConfigEditor.readReporters(it) }
        }.orEmpty()
        val configDir = Path.of(config.configFile.parent.path)
        val workingDir = Path.of(config.workingDir.path)
        val html = reporters.firstOrNull { it.name == ReporterKind.HTML.configName }
        val allure = reporters.firstOrNull { it.name == ReporterKind.ALLURE.configName }
        val htmlDir = configDir.resolve(html?.options?.get("outputFolder") ?: "playwright-report").normalize()
        val allureResultsDir = workingDir.resolve(allure?.options?.get("resultsDir") ?: "allure-results").normalize()
        return withContext(Dispatchers.IO) {
            Locations(
                config = config,
                htmlConfigured = html != null,
                htmlDir = htmlDir,
                allureConfigured = allure != null,
                allureResultsDir = allureResultsDir,
                allureCli = findPackageFile(workingDir, "allure/cli.js"),
                htmlExists = Files.isRegularFile(htmlDir.resolve("index.html")),
                allureResultsExist = Files.isDirectory(allureResultsDir) &&
                    runCatching { Files.list(allureResultsDir).use { it.findFirst().isPresent } }.getOrDefault(false),
            )
        }
    }

    /** Serves and shows the HTML report. */
    fun showHtml() = cs.launch { mutex.withLock { doShowHtml() } }

    /** Generates, serves and shows the Allure report. */
    fun showAllure() = cs.launch { mutex.withLock { doShowAllure() } }

    /** Reloads the shown report (the Allure report is regenerated). */
    fun reload() = cs.launch {
        mutex.withLock {
            when (shown?.kind) {
                Kind.HTML -> doShowHtml()
                Kind.ALLURE -> doShowAllure()
                null -> Unit
            }
        }
    }

    private suspend fun doShowHtml() {
        val loc = locations() ?: return notifyNoConfig()
        if (!loc.htmlExists) {
            PwNotifications.info(project, PwBundle.message("reports.html.missing", loc.htmlDir.toString()))
            return
        }
        stopAll()
        val server = withContext(Dispatchers.IO) { StaticReportServer(loc.htmlDir) }
        htmlServer = server
        // Cache-busting query so JCEF reloads a regenerated report.
        show(Shown(Kind.HTML, server.url + "?t=" + System.currentTimeMillis()))
    }

    private suspend fun doShowAllure() {
        val loc = locations() ?: return notifyNoConfig()
        val cli = loc.allureCli
        if (cli == null) {
            PwNotifications.info(project, PwBundle.message("reports.allure.notInstalled"))
            return
        }
        if (!loc.allureResultsExist) {
            PwNotifications.info(project, PwBundle.message("reports.allure.noResults", loc.allureResultsDir.toString()))
            return
        }
        stopAll()
        val runner = NodeRunner.getInstance(project)
        val cmd = try {
            runner.nodeCommandLine(loc.config.workingDir.path, listOf(cli.toString(), "serve", "--host", "127.0.0.1", loc.allureResultsDir.toString()))
        } catch (e: PwException) {
            PwNotifications.error(project, e.message ?: "")
            return
        }
        val url = CompletableDeferred<String>()
        val output = StringBuffer()
        val handler = runner.start(cmd, null, object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
                if (outputType != ProcessOutputTypes.STDOUT || url.isCompleted) return
                ALLURE_URL.find(output)?.let { url.complete(it.groupValues[1]) }
            }

            override fun processTerminated(event: ProcessEvent) {
                if (!url.isCompleted) url.completeExceptionally(PwException(output.toString().takeLast(1500)))
            }
        })
        allureHandler = handler
        val served = try {
            withTimeout(ALLURE_START_TIMEOUT_MS) { url.await() }
        } catch (e: Exception) {
            handler.destroyProcess()
            allureHandler = null
            LOG.warn("allure serve failed", e)
            PwNotifications.error(project, PwBundle.message("reports.allure.failed", e.message ?: e.toString()))
            return
        }
        show(Shown(Kind.ALLURE, served))
    }

    private fun onRunFinished() {
        cs.launch {
            delay(500) // let reporters finish writing
            val openAfterRun = PwSettings.getInstance(project).state.openReportAfterRun
            mutex.withLock {
                when {
                    shown?.kind == Kind.ALLURE -> doShowAllure()
                    shown?.kind == Kind.HTML -> doShowHtml()
                    openAfterRun -> {
                        val loc = locations()
                        when {
                            loc == null -> Unit
                            loc.allureConfigured && loc.allureCli != null && loc.allureResultsExist -> doShowAllure()
                            loc.htmlExists -> doShowHtml()
                        }
                    }
                }
            }
            if (openAfterRun && shown != null) {
                withContext(Dispatchers.EDT) { activateTab() }
            }
            publish()
        }
    }

    private suspend fun show(value: Shown) {
        shown = value
        publish()
        withContext(Dispatchers.EDT) { activateTab() }
    }

    private fun activateTab() {
        val window = ToolWindowManager.getInstance(project).getToolWindow("Playwright") ?: return
        val content = window.contentManager.contents.firstOrNull { it.displayName == PwBundle.message("toolwindow.tab.reports") }
        window.show {
            if (content != null) window.contentManager.setSelectedContent(content)
        }
    }

    private fun stopAll() {
        htmlServer?.close()
        htmlServer = null
        allureHandler?.destroyProcess()
        allureHandler = null
        shown = null
    }

    private fun notifyNoConfig() {
        PwNotifications.info(project, PwBundle.message("reports.error.noConfig"))
    }

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(PwTopics.REPORTS_CHANGED).reportsChanged()
    }

    override fun dispose() {
        stopAll()
    }

    companion object {
        private val LOG = logger<ReportsService>()
        private const val ALLURE_START_TIMEOUT_MS = 120_000L

        /** @allurereport/static-server: `Allure is running on ${serverUrl}. Press CTRL+C to stop.` */
        private val ALLURE_URL = Regex("""Allure is running on (http\S+?)\.?\s""")

        fun getInstance(project: Project): ReportsService = project.service()

        /** `node_modules/<relative>` from [start] upwards. */
        fun findPackageFile(start: Path, relative: String): Path? {
            var dir: Path? = start
            while (dir != null) {
                val candidate = dir.resolve("node_modules").resolve(relative)
                if (Files.isRegularFile(candidate)) return candidate
                dir = dir.parent
            }
            return null
        }
    }
}
