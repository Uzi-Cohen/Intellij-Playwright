/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.tests

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PluginResources
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwConfigErrors
import dev.uzi.pw.core.PwConfigsListener
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwTopics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * What the test explorer shows.
 *
 * @property config the selected config, or null when the project has none.
 * @property model the last good listing of [config], kept when a later listing fails.
 * @property loading a listing is running.
 * @property error the message of the last listing failure, or of a test file that failed to load.
 */
data class DiscoveryState(
    val config: PwConfig?,
    val model: TestModel?,
    val loading: Boolean,
    val error: String?,
)

/**
 * Lists the tests of the selected config with `playwright test --list --reporter=<js/pw-list-reporter.cjs>`,
 * groups them like the VS Code Testing view ([TestModel.build]) and re-lists, debounced, when files under a
 * project's testDir change or the configs change. Listing starts with [ensureStarted].
 */
@Service(Service.Level.PROJECT)
class TestDiscovery(private val project: Project, private val cs: CoroutineScope) {

    @Volatile
    private var current = DiscoveryState(null, null, loading = false, error = null)

    @Volatile
    private var started = false

    /** true = list now, false = list after the debounce delay. Conflated: requests during a listing collapse into one. */
    private val requests = Channel<Boolean>(Channel.CONFLATED)

    /** Config paths whose load failure was already notified; cleared when the config lists again. */
    private val notifiedFailure = ConcurrentHashMap.newKeySet<String>()

    init {
        cs.launch {
            for (immediate in requests) {
                if (!immediate) {
                    delay(DEBOUNCE_MS)
                    requests.tryReceive()
                }
                try {
                    list()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Cannot list Playwright tests", e)
                }
            }
        }
        val connection = project.messageBus.connect(cs)
        connection.subscribe(VirtualFileManager.VFS_CHANGES_BG, object : BulkFileListenerBackgroundable {
            override fun after(events: List<VFileEvent>) {
                if (started && events.any { isRelevant(it) }) requests.trySend(false)
            }
        })
        connection.subscribe(PwTopics.CONFIGS_CHANGED, PwConfigsListener {
            if (started) requests.trySend(false)
        })
    }

    fun state(): DiscoveryState = current

    /** Starts listing (once); later calls do nothing. */
    fun ensureStarted() {
        if (started) return
        started = true
        requests.trySend(true)
    }

    /** Lists again now. */
    fun refresh() {
        started = true
        requests.trySend(true)
    }

    private suspend fun list() {
        val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()
        if (config == null) {
            update(DiscoveryState(null, null, loading = false, error = null))
            return
        }
        val previous = current.model?.takeIf { it.configPath == config.configFile.path }
        if (!config.isSupported) {
            update(DiscoveryState(config, null, loading = false, error = null))
            return
        }
        update(DiscoveryState(config, previous, loading = true, error = current.error.takeIf { previous != null }))

        val configPath = config.configFile.path
        try {
            val reporter = withContext(Dispatchers.IO) { PluginResources.extractJs(LIST_REPORTER) }
            val runner = NodeRunner.getInstance(project)
            val cmd = runner.commandLine(config, listOf("test", "--list", "--reporter=$reporter", "-c", configPath))
            val output = runner.runCapture(cmd)
            val parsed = parse(output.stdout)
            if (parsed == null) {
                LOG.warn("No tests listed for $configPath: exit=${output.exitCode} stderr=${output.stderr.take(2000)}")
                val combined = output.stderr + "\n" + output.stdout
                if (notifiedFailure.add(configPath)) PwConfigErrors.notifyIfConfigLoadFailed(project, config, combined)
                val message = errorMessages(output.stdout).firstOrNull()
                    ?: combined.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(500)
                    ?: PwBundle.message("tests.error.unknown")
                update(DiscoveryState(config, previous, loading = false, error = message))
                return
            }
            notifiedFailure.remove(configPath)
            val model = TestModel.build(configPath, parsed.projects, parsed.tests, rootFor(config, parsed.projects))
            update(DiscoveryState(config, model, loading = false, error = parsed.errors.firstOrNull()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PwException) {
            update(DiscoveryState(config, previous, loading = false, error = e.message))
        } catch (e: Exception) {
            LOG.warn("Cannot list tests of $configPath", e)
            update(DiscoveryState(config, previous, loading = false, error = e.message ?: e.toString()))
        }
    }

    private fun update(state: DiscoveryState) {
        current = state
        if (!project.isDisposed) project.messageBus.syncPublisher(TestTopics.MODEL_CHANGED).testModelChanged()
    }

    /** The common ancestor of the projects' testDirs, else the config's working directory. */
    private fun rootFor(config: PwConfig, projects: List<ListedProject>): String {
        val dirs = projects.map { FileUtil.toSystemIndependentName(it.testDir).trimEnd('/') }.filter { it.isNotEmpty() }
        if (dirs.isEmpty()) return config.workingDir.path
        var common = dirs.first().split('/')
        for (dir in dirs.drop(1)) {
            val parts = dir.split('/')
            var i = 0
            while (i < common.size && i < parts.size && pathKey(common[i]) == pathKey(parts[i])) i++
            common = common.take(i)
        }
        val joined = common.joinToString("/")
        return joined.ifEmpty { config.workingDir.path }
    }

    /** A source change under a testDir (not in node_modules or an outputDir) of the current model. */
    private fun isRelevant(event: VFileEvent): Boolean {
        val model = current.model ?: return false
        val paths = buildList {
            add(event.path)
            when (event) {
                is VFileMoveEvent -> add(event.oldPath)
                is VFilePropertyChangeEvent -> if (event.isRename) add(event.oldPath)
                else -> {}
            }
        }
        val testDirs = model.projects.map { pathKey(it.testDir).trimEnd('/') }.filter { it.isNotEmpty() }
        val outputDirs = model.projects.map { pathKey(it.outputDir).trimEnd('/') }.filter { it.isNotEmpty() }
        return paths.any { raw ->
            val path = pathKey(raw)
            if (path.contains("/node_modules/")) return@any false
            if (testDirs.none { path == it || path.startsWith("$it/") }) return@any false
            if (outputDirs.any { path == it || path.startsWith("$it/") }) return@any false
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "")
            ext in SOURCE_EXTENSIONS || !name.contains('.') || event.file?.isDirectory == true
        }
    }

    internal class ParsedList(val projects: List<ListedProject>, val tests: List<ListedTest>, val errors: List<String>)

    companion object {
        private val LOG = logger<TestDiscovery>()
        private const val LIST_REPORTER = "pw-list-reporter.cjs"
        private const val MARKER = "PW_TOOLKIT_TESTS "
        private const val ERROR_MARKER = "PW_TOOLKIT_TESTS_ERROR "
        private const val DEBOUNCE_MS = 1000L
        private val SOURCE_EXTENSIONS = setOf("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs")

        fun getInstance(project: Project): TestDiscovery = project.service()

        /** Messages of the `PW_TOOLKIT_TESTS_ERROR {...}` lines (load errors reported to `onError`). */
        private fun errorMessages(stdout: String): List<String> =
            stdout.lineSequence().filter { it.startsWith(ERROR_MARKER + "{") }.mapNotNull { line ->
                try {
                    val obj = Json.parseToJsonElement(line.removePrefix(ERROR_MARKER)).jsonObject
                    obj["message"]?.jsonPrimitive?.contentOrNull?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                } catch (e: Exception) {
                    null
                }
            }.toList()

        /** Parses the `PW_TOOLKIT_TESTS {"projects":[...],"tests":[...]}` line, or null when there is none. */
        internal fun parse(stdout: String): ParsedList? {
            val line = stdout.lineSequence().firstOrNull { it.startsWith(MARKER + "{") } ?: return null
            return try {
                val root = Json.parseToJsonElement(line.removePrefix(MARKER)).jsonObject
                val projects = (root["projects"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val p = element as? JsonObject ?: return@mapNotNull null
                    ListedProject(
                        name = p.string("name").orEmpty(),
                        testDir = p.string("testDir").orEmpty(),
                        outputDir = p.string("outputDir").orEmpty(),
                    )
                }
                val tests = (root["tests"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val t = element as? JsonObject ?: return@mapNotNull null
                    val file = t.string("file")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val titlePath = (t["titlePath"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                    if (titlePath.isEmpty()) return@mapNotNull null
                    val describes = (t["describes"] as? JsonArray).orEmpty().mapNotNull { d ->
                        val obj = d as? JsonObject ?: return@mapNotNull null
                        ListedDescribe(obj.string("title").orEmpty(), obj.int("line"), obj.int("column"))
                    }
                    ListedTest(
                        id = t.string("id"),
                        projectName = t.string("projectName").orEmpty(),
                        file = file,
                        line = t.int("line"),
                        column = t.int("column"),
                        titlePath = titlePath,
                        describes = describes,
                    )
                }
                ParsedList(projects, tests, errorMessages(stdout))
            } catch (e: Exception) {
                LOG.warn("Cannot parse list reporter output", e)
                null
            }
        }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.int(name: String): Int =
            (this[name] as? JsonPrimitive)?.intOrNull ?: 0
    }
}
