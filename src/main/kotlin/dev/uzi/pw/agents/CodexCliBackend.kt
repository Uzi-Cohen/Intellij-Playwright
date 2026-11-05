/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwEnvironment
import dev.uzi.pw.core.PwException
import dev.uzi.pw.settings.PwSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Codex in non-interactive mode (PLAN 9.2): `codex exec --json --sandbox workspace-write "<prompt>"`.
 * Codex has no `@agent` mention; subagents are spawned by asking for them by their snake_case name.
 * Needs Playwright 1.61+ (`init-agents --loop=codex`) and a git repository unless the settings checkbox
 * adds `--skip-git-repo-check`.
 */
class CodexCliBackend(private val project: Project) : AgentBackend {
    override val id: String = AgentBackends.CODEX
    override val displayName: String = PwBundle.message("agents.backend.codex")

    override fun initLoop(): String = "codex"

    override fun agentFilesPresent(root: Path): Boolean = AgentFiles.codexPresent(root)

    override fun isAvailable(cfg: PwConfig): Availability {
        val version = cfg.version
        if (version == null || !version.atLeast(1, 61)) {
            return Availability.no(PwBundle.message("agents.unavailable.codexVersion", version?.raw ?: "?"))
        }
        val (_, availability) = AgentCli.check(project, this, cfg, BINARY)
        if (!availability.ok) return availability
        if (!skipGitCheck() && !AgentCli.isInsideGitRepo(cfg.workingDir.toNioPath())) {
            return Availability.no(PwBundle.message("agents.unavailable.codexGit"))
        }
        return Availability.OK
    }

    override fun prompt(kind: AgentKind, task: String): String =
        "Spawn the ${kind.codexName} agent to do the following: $task"

    override fun commandLine(kind: AgentKind, prompt: String, cfg: PwConfig): GeneralCommandLine {
        val binary = AgentCli.find(project, BINARY) ?: throw PwException(PwBundle.message("agents.unavailable.cliNotFound", BINARY))
        val params = mutableListOf("exec", "--json", "--sandbox", "workspace-write")
        if (skipGitCheck()) params += "--skip-git-repo-check"
        params += prompt
        return GeneralCommandLine()
            .withExePath(binary)
            .withParameters(params)
            .withWorkDirectory(cfg.workingDir.path)
            // The agent starts the Playwright MCP server, which loads the config: give it the user's Playwright env.
            .withEnvironment(PwEnvironment.resolve(project, cfg))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }

    private fun skipGitCheck(): Boolean = PwSettings.getInstance(project).state.codexSkipGitCheck

    override fun parseLine(line: String): AgentOutput? {
        if (line.isBlank()) return null
        val event = parseJsonObject(line) ?: return AgentOutput(line, false)
        return when (event.str("type")) {
            "thread.started" -> AgentOutput(PwBundle.message("agents.output.thread", event.str("thread_id") ?: ""), false)
            "turn.completed" -> {
                val usage = event.obj("usage")
                val input = (usage?.get("input_tokens") as? JsonPrimitive)?.content ?: "?"
                val output = (usage?.get("output_tokens") as? JsonPrimitive)?.content ?: "?"
                AgentOutput(PwBundle.message("agents.output.turnCompleted", input, output), false)
            }
            "turn.failed" -> AgentOutput(event.obj("error")?.str("message") ?: line, true)
            "error" -> AgentOutput(event.str("message") ?: line, true)
            "item.started" -> event.obj("item")?.let { started(it) }
            "item.completed" -> event.obj("item")?.let { completed(it) }
            else -> null
        }
    }

    private fun started(item: JsonObject): AgentOutput? = when (item.str("type")) {
        "command_execution" -> AgentOutput("→ $ ${item.str("command") ?: ""}", false)
        "mcp_tool_call" -> AgentOutput("→ ${item.str("server") ?: "?"}: ${item.str("tool") ?: "?"}", false)
        "web_search" -> AgentOutput("→ search ${item.str("query") ?: ""}", false)
        else -> null
    }

    private fun completed(item: JsonObject): AgentOutput? = when (item.str("type")) {
        "agent_message" -> item.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { AgentOutput(it, false) }
        "command_execution" -> {
            val exit = (item["exit_code"] as? JsonPrimitive)?.content
            if (exit != null && exit != "0" && exit != "null") {
                AgentOutput("  ✗ ${item.str("command") ?: ""} (exit $exit) ${item["aggregated_output"].brief()}".trimEnd(), true)
            } else null
        }
        "file_change" -> item.arr("changes")?.mapNotNull { c ->
            val change = c as? JsonObject ?: return@mapNotNull null
            "  ✎ ${change.str("kind") ?: "update"} ${change.str("path") ?: ""}"
        }?.takeIf { it.isNotEmpty() }?.let { AgentOutput(it.joinToString("\n"), false) }
        "mcp_tool_call" -> if (item.str("status") == "failed") {
            AgentOutput("  ✗ ${item.str("server") ?: "?"}: ${item.str("tool") ?: "?"} ${item["error"].brief()}".trimEnd(), true)
        } else null
        "todo_list" -> item.arr("items")?.mapNotNull { t ->
            val todo = t as? JsonObject ?: return@mapNotNull null
            val mark = if (todo.bool("completed") == true) "[x]" else "[ ]"
            "  $mark ${todo.str("text") ?: ""}"
        }?.takeIf { it.isNotEmpty() }?.let { AgentOutput(it.joinToString("\n"), false) }
        "error" -> AgentOutput(item.str("message") ?: item.brief(), true)
        else -> null
    }

    companion object {
        const val BINARY = "codex"
    }
}
