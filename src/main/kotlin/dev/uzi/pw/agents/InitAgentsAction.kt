/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.PwActionBase
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** The files `init-agents` writes for each loop (`generateAgents.ts`), relative to the working directory. */
object AgentFiles {
    fun claudeFiles(root: Path): List<Path> =
        AgentKind.entries.map { root.resolve(".claude").resolve("agents").resolve("${it.agentName}.md") }

    fun codexFiles(root: Path): List<Path> =
        AgentKind.entries.map { root.resolve(".codex").resolve("agents").resolve("${it.codexName}.toml") }

    fun opencodeFiles(root: Path): List<Path> =
        listOf(root.resolve("opencode.json")) +
            AgentKind.entries.map { root.resolve(".opencode").resolve("prompts").resolve("${it.agentName}.md") }

    fun claudePresent(root: Path): Boolean = claudeFiles(root).all { Files.isRegularFile(it) }

    fun codexPresent(root: Path): Boolean = codexFiles(root).all { Files.isRegularFile(it) }

    fun opencodePresent(root: Path): Boolean = opencodeFiles(root).all { Files.isRegularFile(it) }

    /** Presence check for an `init-agents --loop` value. */
    fun presentFor(loop: String, root: Path): Boolean = when (loop) {
        "codex" -> codexPresent(root)
        "opencode" -> opencodePresent(root)
        else -> claudePresent(root)
    }
}

/** Runs `node <cli.js> init-agents --loop=<loop> -c <configFile>` in the working directory. */
object InitAgents {
    private val LOG = logger<InitAgents>()

    /** Why the loop can't be initialized for [config], or null when it can. */
    fun unsupportedReason(project: Project, config: PwConfig): String? {
        val loop = AgentBackends.initLoop(project)
        if (loop == "codex" && config.version?.atLeast(1, 61) != true) {
            return PwBundle.message("agents.unavailable.codexVersion", config.version?.raw ?: "?")
        }
        return null
    }

    fun run(project: Project, config: PwConfig) {
        unsupportedReason(project, config)?.let {
            PwNotifications.warn(project, it)
            return
        }
        val service = AgentService.getInstance(project)
        val loop = AgentBackends.initLoop(project)
        service.scope.launch {
            val cmd = try {
                withContext(Dispatchers.IO) {
                    NodeRunner.getInstance(project).commandLine(
                        config, listOf("init-agents", "--loop=$loop", "-c", config.configFile.path),
                    )
                }
            } catch (e: PwException) {
                LOG.info("init-agents: ${e.message}")
                PwNotifications.error(project, e.message ?: "")
                return@launch
            }
            service.runCommand(PwBundle.message("agents.init.title", loop, config.displayName), cmd) { code ->
                if (code == 0) {
                    val settings = PwSettings.getInstance(project).state
                    settings.lastInitAgentsVersion = config.version?.raw
                    settings.lastInitAgentsLoop = loop
                    refreshAgentFiles(config)
                    PwNotifications.info(project, PwBundle.message("agents.init.done", loop))
                } else {
                    PwNotifications.error(project, PwBundle.message("agents.init.failed", code))
                }
            }
        }
    }

    private fun refreshAgentFiles(config: PwConfig) {
        val root = config.workingDir.toNioPath()
        val dirs = listOf(".claude", ".codex", ".opencode", ".github", "specs").map { root.resolve(it) }.filter { Files.isDirectory(it) }
        VfsUtil.markDirtyAndRefresh(true, false, true, config.workingDir)
        if (dirs.isNotEmpty()) LocalFileSystem.getInstance().refreshNioFiles(dirs, true, true, null)
    }
}

/** Tools menu: initialize the Playwright test agents for the chosen backend. */
class InitAgentsAction : PwActionBase() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        if (!e.presentation.isEnabled) return
        if (AgentService.getInstance(project).isRunning) {
            e.presentation.isEnabled = false
            return
        }
        val config = config(e) ?: return
        InitAgents.unsupportedReason(project, config)?.let {
            e.presentation.isEnabled = false
            e.presentation.description = it
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = config(e) ?: return
        InitAgents.run(project, config)
    }
}
