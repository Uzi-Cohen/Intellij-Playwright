/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwEnvironment
import dev.uzi.pw.core.PwException
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * OpenCode (PLAN 9.2): `opencode run --agent build --format json --dir <workingDir> "@playwright-test-<kind> <task>"`.
 * The subagents are defined in the generated `opencode.json` (`mode: "subagent"`); an `@`-mention invokes one.
 * `--format json` prints raw JSON events, one per line. Their shape isn't documented, so unknown
 * events are skipped and only text, tool calls and errors are shown.
 */
class OpencodeCliBackend(private val project: Project) : AgentBackend {
    override val id: String = AgentBackends.OPENCODE
    override val displayName: String = PwBundle.message("agents.backend.opencode")

    override fun initLoop(): String = "opencode"

    override fun agentFilesPresent(root: Path): Boolean = AgentFiles.opencodePresent(root)

    override fun isAvailable(cfg: PwConfig): Availability = AgentCli.check(project, this, cfg, BINARY).second

    override fun prompt(kind: AgentKind, task: String): String = "@${kind.agentName} $task"

    override fun commandLine(kind: AgentKind, prompt: String, cfg: PwConfig): GeneralCommandLine {
        val binary = AgentCli.find(project, BINARY) ?: throw PwException(PwBundle.message("agents.unavailable.cliNotFound", BINARY))
        return GeneralCommandLine()
            .withExePath(binary)
            .withParameters("run", "--agent", "build", "--format", "json", "--dir", cfg.workingDir.path, prompt)
            .withWorkDirectory(cfg.workingDir.path)
            // The agent starts the Playwright MCP server, which loads the config: give it the user's Playwright env.
            .withEnvironment(PwEnvironment.resolve(project, cfg))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }

    override fun parseLine(line: String): AgentOutput? {
        if (line.isBlank()) return null
        val event = parseJsonObject(line) ?: return AgentOutput(line, false)
        val part = event.obj("part")
        return when (event.str("type")) {
            "text" -> part?.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { AgentOutput(it, false) }
            "tool_use", "tool" -> part?.let { tool(it) }
            "error" -> AgentOutput(errorText(event) ?: line, true)
            else -> null
        }
    }

    private fun tool(part: JsonObject): AgentOutput? {
        val name = part.str("tool") ?: return null
        val state = part.obj("state")
        val status = state?.str("status")
        val title = state?.str("title") ?: state?.obj("input")?.brief() ?: ""
        return if (status == "error") {
            AgentOutput("  ✗ $name ${state.str("error") ?: title}".trimEnd(), true)
        } else {
            AgentOutput("→ $name $title".trimEnd(), false)
        }
    }

    private fun errorText(event: JsonObject): String? {
        val error = event.obj("error") ?: return event.str("message")
        return error.obj("data")?.str("message") ?: error.str("message") ?: error.str("name")
    }

    companion object {
        const val BINARY = "opencode"
    }
}
