/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright 2026 Uzi Cohen
 * Ported to Kotlin for Playwright for IntelliJ from microsoft/playwright-vscode src/playwrightFinder.ts
 * (package lookup order and version parsing). Config discovery and project listing are new.
 */
package dev.uzi.pw.core

import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds `playwright.config.*` files in the project, the Playwright package that serves each one, and the
 * projects (testDir/outputDir) each config defines.
 */
@Service(Service.Level.PROJECT)
class PlaywrightProjectLocator(private val project: Project, private val cs: CoroutineScope) {

    @Volatile
    private var configs: List<PwConfig> = emptyList()
    private val projectsCache = ConcurrentHashMap<String, List<PwProjectInfo>>()
    private val notifiedUnsupported = ConcurrentHashMap.newKeySet<String>()
    private var refreshJob: Job? = null

    init {
        project.messageBus.connect(cs).subscribe(VirtualFileManager.VFS_CHANGES_BG, object : BulkFileListenerBackgroundable {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isRelevant(it) }) {
                    for (event in events) {
                        val path = event.path
                        if (CONFIG_NAMES.any { path.endsWith("/$it") }) projectsCache.remove(path)
                    }
                    refresh()
                }
            }
        })
    }

    fun configs(): List<PwConfig> = configs

    /** The config picked in the tool window, else the first config found. May be unsupported. */
    fun selectedConfig(): PwConfig? {
        val all = configs
        val selected = PwSettings.getInstance(project).state.selectedConfigPath
        return all.firstOrNull { it.configFile.path == selected } ?: all.firstOrNull()
    }

    fun select(config: PwConfig) {
        PwSettings.getInstance(project).state.selectedConfigPath = config.configFile.path
        project.messageBus.syncPublisher(PwTopics.CONFIGS_CHANGED).configsChanged()
    }

    fun cachedProjects(config: PwConfig): List<PwProjectInfo>? = projectsCache[config.configFile.path]

    /** Projects of [config] from `playwright test --list --reporter=json`. Cached until the config changes. */
    suspend fun projects(config: PwConfig): List<PwProjectInfo> {
        projectsCache[config.configFile.path]?.let { return it }
        val cmd = NodeRunner.getInstance(project).commandLine(
            config,
            listOf("test", "--list", "--reporter=json", "-c", config.configFile.path),
        )
        val output = NodeRunner.getInstance(project).runCapture(cmd)
        val result = parseProjects(output.stdout)
        if (result.isEmpty()) {
            LOG.warn("No projects listed for ${config.configFile.path}: exit=${output.exitCode} stderr=${output.stderr.take(2000)}")
            throw PwException(PwBundle.message("locator.error.noProjects", config.displayName, output.stderr.take(500)))
        }
        projectsCache[config.configFile.path] = result
        return result
    }

    /** Rescans configs in the background (debounced) and notifies [PwTopics.CONFIGS_CHANGED]. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = cs.launch {
            delay(300)
            val files = smartReadAction(project) {
                val scope = GlobalSearchScope.projectScope(project)
                CONFIG_NAMES.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                    .filter { f -> !f.path.contains("/node_modules/") }
                    .sortedBy { it.path }
            }
            val found = files.map { resolve(it) }
            configs = found
            project.messageBus.syncPublisher(PwTopics.CONFIGS_CHANGED).configsChanged()
            found.forEach { notifyIfUnsupported(it) }
        }
    }

    private suspend fun resolve(configFile: VirtualFile): PwConfig {
        val workingDir = readAction {
            var dir = configFile.parent
            var result = configFile.parent
            while (dir != null) {
                if (dir.findChild("package.json") != null) {
                    result = dir
                    break
                }
                dir = dir.parent
            }
            result
        }
        val configDir = Path.of(configFile.parent.path)
        val found = findPackage(configDir)
        return PwConfig(
            configFile = configFile,
            workingDir = workingDir,
            packageDir = found?.dir,
            cliJs = found?.dir?.resolve("cli.js")?.takeIf { Files.isRegularFile(it) },
            version = found?.version,
            packageName = found?.name,
        )
    }

    private class FoundPackage(val name: String, val dir: Path, val version: PwVersion?)

    /** Node-style lookup, walking up from [start], in playwright-vscode's package order. */
    private fun findPackage(start: Path): FoundPackage? {
        var dir: Path? = start
        while (dir != null) {
            for (name in PACKAGES) {
                val pkgDir = dir.resolve("node_modules").resolve(name)
                val pkgJson = pkgDir.resolve("package.json")
                if (Files.isRegularFile(pkgJson)) {
                    val version = try {
                        val obj = Json.parseToJsonElement(Files.readString(pkgJson)).jsonObject
                        obj["version"]?.jsonPrimitive?.contentOrNull?.let { PwVersion.parse(it) }
                    } catch (e: Exception) {
                        LOG.warn("Cannot read $pkgJson", e)
                        null
                    }
                    return FoundPackage(name, pkgDir, version)
                }
            }
            dir = dir.parent
        }
        return null
    }

    private fun notifyIfUnsupported(config: PwConfig) {
        if (config.isSupported) return
        if (!notifiedUnsupported.add(config.configFile.path)) return
        val found = config.version?.raw ?: PwBundle.message("locator.version.notFound")
        val command = "npm install -D @playwright/test@latest"
        PwNotifications.warn(
            project,
            PwBundle.message("locator.notify.unsupported", MIN_PLAYWRIGHT.raw, found, config.displayName),
            NotificationAction.createSimple(PwBundle.message("locator.notify.copyUpdate")) {
                CopyPasteManager.getInstance().setContents(StringSelection(command))
            },
            NotificationAction.createSimple(PwBundle.message("locator.notify.openPackageJson")) {
                val pkg = LocalFileSystem.getInstance().findFileByPath(config.workingDir.path + "/package.json")
                if (pkg != null) FileEditorManager.getInstance(project).openFile(pkg, true)
            },
        )
    }

    private fun isRelevant(event: VFileEvent): Boolean {
        val path = event.path
        if (CONFIG_NAMES.any { path.endsWith("/$it") }) return true
        return PACKAGES.any { path.endsWith("/node_modules/$it/package.json") }
    }

    companion object {
        private val LOG = logger<PlaywrightProjectLocator>()

        val CONFIG_NAMES = listOf(
            "playwright.config.ts", "playwright.config.js", "playwright.config.mjs",
            "playwright.config.mts", "playwright.config.cjs", "playwright.config.cts",
        )

        /** From playwright-vscode src/playwrightFinder.ts, without the component-testing packages. */
        private val PACKAGES = listOf("@playwright/test", "playwright")

        fun getInstance(project: Project): PlaywrightProjectLocator = project.service()

        /** Parses `config.projects[].{name,testDir,outputDir}` from the JSON reporter output. */
        internal fun parseProjects(stdout: String): List<PwProjectInfo> {
            val start = stdout.indexOf('{')
            if (start < 0) return emptyList()
            return try {
                val root = Json.parseToJsonElement(stdout.substring(start)).jsonObject
                val projects = root["config"]?.jsonObject?.get("projects") as? JsonArray ?: return emptyList()
                projects.mapNotNull { element ->
                    val p = element as? JsonObject ?: return@mapNotNull null
                    val testDir = p["testDir"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val outputDir = p["outputDir"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PwProjectInfo(
                        name = p["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        testDir = Path.of(testDir),
                        outputDir = Path.of(outputDir),
                    )
                }
            } catch (e: Exception) {
                LOG.warn("Cannot parse JSON reporter output", e)
                emptyList()
            }
        }
    }
}

