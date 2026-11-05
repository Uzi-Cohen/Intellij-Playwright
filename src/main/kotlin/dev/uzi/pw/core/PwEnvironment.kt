/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.uzi.pw.run.PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID
import dev.uzi.pw.settings.PwSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Environment variables for every Playwright process the plugin starts (run-server, test --list, init-agents,
 * show-trace, codegen, the agent CLIs and the MCP server they launch).
 *
 * playwright-vscode passes its `playwright.env` setting to every process it spawns (src/extension.ts `_envProvider`).
 * In IntelliJ the variables a config needs usually live in the Playwright run configurations, so those are used too.
 * Later sources override earlier ones:
 * 1. Playwright run configuration template (env file, then variables), when "use run configuration env" is on.
 * 2. The first Playwright run configuration whose config file is [PwConfig.configFile] (env file, then variables).
 * 3. The plugin's `.env` file setting.
 * 4. The plugin's environment variables setting.
 */
object PwEnvironment {
    private val LOG = logger<PwEnvironment>()

    fun resolve(project: Project, config: PwConfig?): Map<String, String> {
        val settings = PwSettings.getInstance(project)
        val result = LinkedHashMap<String, String>()
        if (settings.state.envFromRunConfigurations) {
            runCatching { fromRunConfigurations(project, config) }
                .onFailure { LOG.info("Cannot read Playwright run configuration env: ${it.message}") }
                .getOrNull()?.let { result.putAll(it) }
        }
        settings.state.envFile?.takeIf { it.isNotBlank() }?.let { result.putAll(readDotEnv(resolvePath(it, config))) }
        result.putAll(settings.envVars())
        return result
    }

    private fun fromRunConfigurations(project: Project, config: PwConfig?): Map<String, String> {
        val type = ConfigurationTypeUtil.findConfigurationType(PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID) ?: return emptyMap()
        val runManager = RunManager.getInstance(project)
        val result = LinkedHashMap<String, String>()
        type.configurationFactories.firstOrNull()?.let { factory ->
            envOf(runManager.getConfigurationTemplate(factory).configuration, config)?.let { result.putAll(it) }
        }
        val configPath = config?.configFile?.path
        val matching = runManager.getConfigurationsList(type).firstOrNull { rc ->
            configPath != null && configFilePath(rc)?.replace('\\', '/') == configPath
        }
        matching?.let { envOf(it, config)?.let { env -> result.putAll(env) } }
        return result
    }

    /** The run configuration's env file (when set) and env variables, read through its public settings API. */
    private fun envOf(rc: RunConfiguration, config: PwConfig?): Map<String, String>? {
        val settings = rc.javaClass.getMethod("getSettings").invoke(rc)
        val data = settings.javaClass.getMethod("getEnvData").invoke(settings) as? EnvironmentVariablesData ?: return null
        val result = LinkedHashMap<String, String>()
        data.environmentFile?.takeIf { it.isNotBlank() }?.let { result.putAll(readDotEnv(resolvePath(it, config))) }
        result.putAll(data.envs)
        return result
    }

    private fun configFilePath(rc: RunConfiguration): String? = try {
        val settings = rc.javaClass.getMethod("getSettings").invoke(rc)
        settings.javaClass.getMethod("getConfigFilePath").invoke(settings) as String?
    } catch (e: Exception) {
        null
    }

    private fun resolvePath(path: String, config: PwConfig?): Path {
        val p = Path.of(path)
        return if (p.isAbsolute || config == null) p else Path.of(config.workingDir.path).resolve(p).normalize()
    }

    /** Minimal dotenv parser: `KEY=VALUE`, optional `export `, `#` comments, single/double quoted values. */
    fun readDotEnv(file: Path): Map<String, String> {
        if (!Files.isRegularFile(file)) return emptyMap()
        val result = LinkedHashMap<String, String>()
        try {
            for (raw in Files.readAllLines(file)) {
                val line = raw.trim().removePrefix("export ").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                var value = line.substring(eq + 1).trim()
                value = when {
                    value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                        value.substring(1, value.length - 1).replace("\\n", "\n").replace("\\\"", "\"")
                    value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1)
                    else -> value.substringBefore(" #").trim()
                }
                result[key] = value
            }
        } catch (e: Exception) {
            LOG.info("Cannot read env file $file: ${e.message}")
        }
        return result
    }
}
