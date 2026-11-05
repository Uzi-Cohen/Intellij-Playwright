/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.ui.ConsoleView
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Runs Node processes for the plugin: always `node <cli.js> ...` directly, never `npx`.
 *
 * The interpreter is the settings override, else the IDE's Node interpreter, else `node` on PATH.
 */
@Service(Service.Level.PROJECT)
class NodeRunner(private val project: Project) {

    /** Path to the node executable, or null when none can be found. */
    fun nodePath(): String? {
        PwSettings.getInstance(project).state.nodeInterpreterPath?.takeIf { it.isNotBlank() }?.let { return it }
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
        if (interpreter is NodeJsLocalInterpreter) return interpreter.interpreterSystemDependentPath
        val name = if (SystemInfo.isWindows) "node.exe" else "node"
        return PathEnvironmentVariableUtil.findInPath(name)?.absolutePath
    }

    /** `node <cli.js> args...` in the config's working directory. */
    fun commandLine(config: PwConfig, args: List<String>, env: Map<String, String> = emptyMap()): GeneralCommandLine {
        val cli = config.cliJs ?: throw PwException(PwBundle.message("node.error.noPlaywright", config.displayName))
        return nodeCommandLine(config.workingDir.path, listOf(cli.toString()) + args, env, config)
    }

    /**
     * `node args...` in [workDir], with the user's Playwright environment ([PwEnvironment]) and then [env] on top.
     * [config] picks the run configuration env; defaults to the selected config.
     */
    fun nodeCommandLine(
        workDir: String,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        config: PwConfig? = PlaywrightProjectLocator.getInstance(project).selectedConfig(),
    ): GeneralCommandLine {
        val node = nodePath() ?: throw PwException(PwBundle.message("node.error.noNode"))
        return GeneralCommandLine(listOf(node) + args)
            .withWorkingDirectory(java.nio.file.Path.of(workDir))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(PwEnvironment.resolve(project, config))
            .withEnvironment(env)
            .withCharset(StandardCharsets.UTF_8)
    }

    /** Starts [cmd], attaches [console] and [listener], and calls `startNotify()`. Stop with `destroyProcess()`. */
    fun start(cmd: GeneralCommandLine, console: ConsoleView? = null, listener: ProcessListener? = null): KillableColoredProcessHandler {
        val handler = KillableColoredProcessHandler(cmd)
        handler.setShouldKillProcessSoftly(true)
        console?.attachToProcess(handler)
        listener?.let { handler.addProcessListener(it) }
        handler.startNotify()
        return handler
    }

    /** Runs [cmd] to completion on a background thread and returns its output. */
    suspend fun runCapture(cmd: GeneralCommandLine, timeoutMs: Int = 120_000): ProcessOutput =
        withContext(Dispatchers.IO) {
            CapturingProcessHandler(cmd).runProcess(timeoutMs)
        }

    fun createConsole(): ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    companion object {
        fun getInstance(project: Project): NodeRunner = project.service()
    }
}
