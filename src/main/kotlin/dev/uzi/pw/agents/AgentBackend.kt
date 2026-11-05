/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.settings.PwSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Whether a backend can run, and if not, why (shown in the Agents tab). */
data class Availability(val ok: Boolean, val reason: String?) {
    companion object {
        val OK = Availability(true, null)
        fun no(reason: String) = Availability(false, reason)
    }
}

/** One piece of readable console text produced from a line of agent output. */
data class AgentOutput(val text: String, val isError: Boolean)

/**
 * An agent CLI that runs the Playwright test agents written by `init-agents` (PLAN 9.1, 9.2).
 * Implementations never go through a shell: the prompt is a single argv element.
 */
interface AgentBackend {
    /** Settings id: `CLAUDE`, `CODEX` or `OPENCODE`. */
    val id: String

    /** Name shown to the user. */
    val displayName: String

    /** Value for `init-agents --loop=<loop>`. */
    fun initLoop(): String

    /** True when the agent files `init-agents` writes for this loop exist under [root]. */
    fun agentFilesPresent(root: Path): Boolean

    /** Availability for [cfg] (PLAN 9.3). Touches the file system; call it off the EDT. */
    fun isAvailable(cfg: PwConfig): Availability

    /** The full prompt that makes the CLI hand [task] to the agent of [kind]. */
    fun prompt(kind: AgentKind, task: String): String

    /** The process to start. The working directory is [PwConfig.workingDir]. */
    fun commandLine(kind: AgentKind, prompt: String, cfg: PwConfig): GeneralCommandLine

    /** Turns one stdout line into console text, or null to print nothing for it. */
    fun parseLine(line: String): AgentOutput?
}

object AgentBackends {
    const val CLAUDE = "CLAUDE"
    const val CODEX = "CODEX"
    const val OPENCODE = "OPENCODE"
    const val MCP_DIRECT = "MCP_DIRECT"

    /** The backend chosen in settings, or null for backend B (`MCP_DIRECT`), which is not implemented yet. */
    fun selected(project: Project): AgentBackend? = when (PwSettings.getInstance(project).state.agentBackend) {
        CODEX -> CodexCliBackend(project)
        OPENCODE -> OpencodeCliBackend(project)
        MCP_DIRECT -> null
        else -> ClaudeCliBackend(project)
    }

    /** The `--loop` to initialize for the chosen backend. Backend B reads the Claude agent files. */
    fun initLoop(project: Project): String = selected(project)?.initLoop() ?: "claude"

    /** Availability of the chosen backend for [cfg]. Call it off the EDT. */
    fun availability(project: Project, cfg: PwConfig?): Availability {
        if (cfg == null) return Availability.no(PwBundle.message("agents.unavailable.noConfig"))
        if (!cfg.isSupported) return Availability.no(PwBundle.message("agents.unavailable.unsupported", cfg.displayName))
        val backend = selected(project) ?: return Availability.no(PwBundle.message("agents.unavailable.mcpDirect"))
        return backend.isAvailable(cfg)
    }
}

/** Finds an agent CLI binary: `PwSettings.agentCliPath` first, then `PATH`. */
object AgentCli {
    fun find(project: Project, name: String): String? {
        val custom = PwSettings.getInstance(project).state.agentCliPath?.trim()
        if (!custom.isNullOrEmpty()) return custom.takeIf { File(it).isFile }
        if (SystemInfo.isWindows) {
            val found = PathEnvironmentVariableUtil.findExecutableInWindowsPath(name)
            return found.takeIf { it != name && File(it).isFile }
        }
        return PathEnvironmentVariableUtil.findInPath(name)?.absolutePath
    }

    fun notFound(project: Project, name: String): Availability {
        val custom = PwSettings.getInstance(project).state.agentCliPath?.trim()
        return if (!custom.isNullOrEmpty()) Availability.no(PwBundle.message("agents.unavailable.customCliMissing", custom))
        else Availability.no(PwBundle.message("agents.unavailable.cliNotFound", name))
    }

    /** Common checks: agent files present, then the binary. Returns the binary path or the reason it can't run. */
    fun check(project: Project, backend: AgentBackend, cfg: PwConfig, binaryName: String): Pair<String?, Availability> {
        val root = cfg.workingDir.toNioPath()
        if (!backend.agentFilesPresent(root)) {
            return null to Availability.no(PwBundle.message("agents.unavailable.filesMissing", backend.initLoop()))
        }
        val binary = find(project, binaryName) ?: return null to notFound(project, binaryName)
        return binary to Availability.OK
    }

    fun isInsideGitRepo(dir: Path): Boolean {
        var current: Path? = dir.toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return true
            current = current.parent
        }
        return false
    }
}

internal fun parseJsonObject(line: String): JsonObject? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        Json.parseToJsonElement(trimmed) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** A one-line, length-limited rendering of a JSON value for tool inputs and results. */
internal fun JsonElement?.brief(max: Int = 160): String {
    val text = when (this) {
        null -> ""
        is JsonPrimitive -> if (isString) content else toString()
        else -> toString()
    }.replace(Regex("\\s+"), " ").trim()
    return if (text.length <= max) text else text.take(max - 1) + "…"
}
