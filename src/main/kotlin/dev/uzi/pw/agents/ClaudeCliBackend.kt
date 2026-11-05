/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Claude Code in print mode (PLAN 9.2):
 * `claude -p <prompt> --output-format stream-json --verbose --permission-mode acceptEdits
 *  --mcp-config <json> --strict-mcp-config --allowedTools mcp__playwright-test Read Glob Grep LS Edit MultiEdit Write`.
 * The MCP server is our own definition that starts `node <cli.js> run-test-mcp-server -c <config>` directly,
 * under the name `playwright-test` that the generated agent files reference.
 */
class ClaudeCliBackend(private val project: Project) : AgentBackend {
    override val id: String = AgentBackends.CLAUDE
    override val displayName: String = PwBundle.message("agents.backend.claude")

    override fun initLoop(): String = "claude"

    override fun agentFilesPresent(root: Path): Boolean = AgentFiles.claudePresent(root)

    override fun isAvailable(cfg: PwConfig): Availability {
        val (_, availability) = AgentCli.check(project, this, cfg, BINARY)
        if (!availability.ok) return availability
        if (cfg.cliJs == null) return Availability.no(PwBundle.message("agents.unavailable.noCli"))
        if (NodeRunner.getInstance(project).nodePath() == null) return Availability.no(PwBundle.message("agents.unavailable.noNode"))
        return Availability.OK
    }

    override fun prompt(kind: AgentKind, task: String): String = "@agent-${kind.agentName} $task"

    override fun commandLine(kind: AgentKind, prompt: String, cfg: PwConfig): GeneralCommandLine {
        val binary = AgentCli.find(project, BINARY) ?: throw PwException(PwBundle.message("agents.unavailable.cliNotFound", BINARY))
        val node = NodeRunner.getInstance(project).nodePath() ?: throw PwException(PwBundle.message("agents.unavailable.noNode"))
        val cli = cfg.cliJs ?: throw PwException(PwBundle.message("agents.unavailable.noCli"))
        val mcpConfig = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(MCP_SERVER) {
                    put("command", node)
                    putJsonArray("args") {
                        add(cli.toString())
                        add("run-test-mcp-server")
                        add("-c")
                        add(cfg.configFile.path)
                    }
                }
            }
        }.toString()
        return GeneralCommandLine()
            .withExePath(binary)
            .withParameters(
                "-p", prompt,
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", "acceptEdits",
                "--mcp-config", mcpConfig,
                "--strict-mcp-config",
                "--allowedTools", "mcp__$MCP_SERVER", "Read", "Glob", "Grep", "LS", "Edit", "MultiEdit", "Write",
            )
            .withWorkDirectory(cfg.workingDir.path)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }

    override fun parseLine(line: String): AgentOutput? {
        if (line.isBlank()) return null
        val event = parseJsonObject(line) ?: return AgentOutput(line, false)
        return when (event.str("type")) {
            "system" -> system(event)
            "assistant" -> assistant(event)
            "user" -> toolResults(event)
            "result" -> result(event)
            else -> null
        }
    }

    private fun system(event: JsonObject): AgentOutput? {
        if (event.str("subtype") != "init") return null
        val lines = mutableListOf<String>()
        var error = false
        event.str("model")?.let { lines += PwBundle.message("agents.output.session", it) }
        event.arr("mcp_servers")?.forEach { server ->
            val s = server as? JsonObject ?: return@forEach
            val name = s.str("name") ?: return@forEach
            val status = s.str("status") ?: "?"
            if (status != "connected") error = true
            lines += PwBundle.message("agents.output.mcpServer", name, status)
        }
        return if (lines.isEmpty()) null else AgentOutput(lines.joinToString("\n"), error)
    }

    private fun assistant(event: JsonObject): AgentOutput? {
        val nested = event["parent_tool_use_id"].let { it is JsonPrimitive && it.isString }
        val indent = if (nested) "  " else ""
        val content = event.obj("message")?.arr("content") ?: return null
        val parts = mutableListOf<String>()
        for (block in content) {
            val b = block as? JsonObject ?: continue
            when (b.str("type")) {
                "text" -> b.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it.prependIndent(indent) }
                "tool_use" -> parts += indent + toolUse(b)
            }
        }
        return if (parts.isEmpty()) null else AgentOutput(parts.joinToString("\n"), false)
    }

    private fun toolUse(block: JsonObject): String {
        val name = block.str("name") ?: "?"
        val input = block.obj("input")
        val subagent = input?.str("subagent_type")
        if (subagent != null) {
            return "→ $subagent: ${input.str("description") ?: ""}".trimEnd()
        }
        val shown = if (name.startsWith("mcp__")) name.removePrefix("mcp__").replaceFirst("__", ": ") else name
        val detail = input?.let { i -> i.str("file_path") ?: i.str("url") ?: i.str("pattern") ?: i.str("path") ?: i.brief() } ?: ""
        return "→ $shown $detail".trimEnd()
    }

    private fun toolResults(event: JsonObject): AgentOutput? {
        val content = event.obj("message")?.arr("content") ?: return null
        val errors = content.mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            if (b.str("type") == "tool_result" && b.bool("is_error") == true) "  ✗ " + b["content"].brief() else null
        }
        return if (errors.isEmpty()) null else AgentOutput(errors.joinToString("\n"), true)
    }

    private fun result(event: JsonObject): AgentOutput {
        val isError = event.bool("is_error") == true || event.str("subtype")?.startsWith("error") == true
        val text = event.str("result")?.trim().orEmpty()
        val cost = (event["total_cost_usd"] as? JsonPrimitive)?.content
        val summary = if (isError) PwBundle.message("agents.output.failed", event.str("subtype") ?: "error")
        else PwBundle.message("agents.output.done", cost ?: "?")
        return AgentOutput(if (text.isEmpty()) summary else "$text\n$summary", isError)
    }

    companion object {
        const val BINARY = "claude"
        const val MCP_SERVER = "playwright-test"
    }
}
