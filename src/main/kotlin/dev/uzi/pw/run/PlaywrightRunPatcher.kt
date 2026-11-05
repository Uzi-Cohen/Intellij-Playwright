/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.run

/*
 * The one program runner that changes Playwright test runs started from the IDE.
 *
 * What javap showed in IDEA 2026.1.5 (261.27258.48), plugins/javascript-plugin/lib/javascript-plugin.jar:
 *
 * - Run configuration: com.intellij.aqua.runners.playwright.js.PlaywrightRunConfiguration (final)
 *     extends com.intellij.aqua.runners.js.core.JsTestRunConfiguration<PlaywrightRunSettings>
 *     extends com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile.
 *   Type: PlaywrightConfigurationType, id "JavaScriptTestRunnerPlaywright".
 *   Public: `PlaywrightRunSettings getSettings()`, `void setSettings(PlaywrightRunSettings)`. The settings are read
 *   once in the PlaywrightRunProfileState constructor, i.e. inside `getState()`.
 *   The classes live in the JavaScript plugin's content module `intellij.aqua.runners.playwright.js`, declared
 *   `visibility="internal"`, so everything below goes through the run configuration type id and reflection.
 *
 * - Settings: JsTestRunSettings<S> has public getters `getConfigFilePath, getWorkingDir, getCliOptions,
 *   getEnvData, ...` and `builder()`. JsTestRunSettingsBuilder<S> has `setCliOptions(String)`,
 *   `setEnvData(EnvironmentVariablesData)`, ... and `build()`.
 *
 * - Command line: PlaywrightRunProfileState.configureCommandLine adds node options, `<playwright cli>`, `test`,
 *   `-c <config>`, the test paths / `-g` pattern, and then `ParametersListUtil.parse(settings.getCliOptions())`.
 *   Extra CLI args therefore reach `playwright test`; when an option is repeated, the last one wins (commander).
 *
 * - JavaScript.nodeRunConfigurationExtension is never called for Playwright runs (PlaywrightRunProfileState does not
 *   use NodeRunConfigurationExtensionsManager), and ExecutionListener.processStartScheduled runs after getState(),
 *   so neither can change the command line.
 *
 * Approach: this runner is registered `order="first"` and claims Playwright configurations while any
 * [RunPatchContributor] is active. `execute` asks every contributor for a [RunPatch] (in the background, since a
 * contributor may need to start a browser), clones the configuration, appends the CLI args to the clone's CLI
 * options and merges the env vars into its env data, and hands the clone to the runner that would have run it.
 * The user's run configuration is never modified.
 */

import com.intellij.execution.ExecutionException
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwProjectScope
import dev.uzi.pw.trace.TraceRunPatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Id of PlaywrightConfigurationType in the bundled JavaScript plugin. */
const val PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID: String = "JavaScriptTestRunnerPlaywright"

class PlaywrightRunPatcher : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (!isPlaywright(profile)) return false
        val project = (profile as RunConfiguration).project
        if (project.isDisposed || CONTRIBUTORS.none { it.isActive(project) }) return false
        return delegateFor(executorId, profile) != null
    }

    @Throws(ExecutionException::class)
    override fun execute(environment: ExecutionEnvironment) {
        val profile = environment.runProfile as? RunConfiguration
            ?: throw ExecutionException(PwBundle.message("run.error.noRunner", environment.runProfile.name))
        val executorId = environment.executor.id
        val delegate = delegateFor(executorId, profile)
            ?: throw ExecutionException(PwBundle.message("run.error.noRunner", profile.name))
        val project = environment.project
        val context = RunPatchContext(project, profile, executorId, userCliArgs(profile), matchConfig(project, profile))

        PwProjectScope.getInstance(project).cs.launch {
            val active = CONTRIBUTORS.filter { it.isActive(project) }
            var patch = RunPatch()
            try {
                for (contributor in active) contributor.patch(context)?.let { patch += it }
            } catch (e: Exception) {
                LOG.warn("Cannot prepare Playwright run '${profile.name}'", e)
                PwNotifications.warn(project, PwBundle.message("run.warn.patchFailed", e.message ?: e.toString()))
            }
            withContext(Dispatchers.EDT) {
                launchPatched(environment, delegate, profile, patch, context, active)
            }
        }
    }

    private fun launchPatched(
        environment: ExecutionEnvironment,
        delegate: ProgramRunner<*>,
        profile: RunConfiguration,
        patch: RunPatch,
        context: RunPatchContext,
        active: List<RunPatchContributor>,
    ) {
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) active.forEach { runCatching { it.onRunFinished(context) } }
        }

        val patched = if (patch.isEmpty) null else withPatch(profile, patch)
        val original = environment.callback
        val callback = object : ProgramRunner.Callback {
            override fun processStarted(descriptor: RunContentDescriptor?) {
                original?.processStarted(descriptor)
                val handler = descriptor?.processHandler
                if (handler == null) {
                    finish()
                    return
                }
                active.forEach { runCatching { it.onRunStarted(context) } }
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) = finish()
                })
                if (handler.isProcessTerminated) finish()
            }

            override fun processNotStarted(error: Throwable?) {
                original?.processNotStarted(error)
                finish()
            }
        }

        try {
            val builder = ExecutionEnvironmentBuilder(environment)
            val settings = environment.runnerAndConfigurationSettings
            if (settings != null) builder.runnerAndSettings(delegate, settings) else builder.runner(delegate)
            builder.runProfile(patched ?: profile).executionId(environment.executionId)
            environment.dataContext?.let { builder.dataContext(it) }
            delegate.execute(builder.build(callback))
        } catch (e: Exception) {
            finish()
            LOG.warn("Cannot start Playwright run '${profile.name}'", e)
            PwNotifications.error(environment.project, e.message ?: e.toString())
        }
    }

    companion object {
        private val LOG = logger<PlaywrightRunPatcher>()
        const val RUNNER_ID: String = "dev.uzi.pw.runPatcher"

        /** Every feature that patches Playwright runs. Order matters only for the order of the added CLI args. */
        private val CONTRIBUTORS: List<RunPatchContributor> = listOf(TraceRunPatch, HeadedRunPatch)

        fun isPlaywright(profile: RunProfile): Boolean =
            profile is RunConfiguration && profile.type.id == PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID

        private fun delegateFor(executorId: String, profile: RunProfile): ProgramRunner<*>? =
            ProgramRunner.PROGRAM_RUNNER_EP.extensionList.firstOrNull {
                it !is PlaywrightRunPatcher && it.canRun(executorId, profile)
            }

        private fun settingsOf(configuration: RunConfiguration): Any =
            configuration.javaClass.getMethod("getSettings").invoke(configuration)

        private fun userCliArgs(configuration: RunConfiguration): List<String> = try {
            val settings = settingsOf(configuration)
            ParametersListUtil.parse((settings.javaClass.getMethod("getCliOptions").invoke(settings) as String?).orEmpty())
        } catch (e: Exception) {
            LOG.debug(e)
            emptyList()
        }

        /** The plugin's PwConfig for the run's config file, else the selected config. */
        private fun matchConfig(project: Project, configuration: RunConfiguration): PwConfig? {
            val locator = PlaywrightProjectLocator.getInstance(project)
            val path = try {
                val settings = settingsOf(configuration)
                settings.javaClass.getMethod("getConfigFilePath").invoke(settings) as String?
            } catch (e: Exception) {
                null
            }?.replace('\\', '/')
            return locator.configs().firstOrNull { path != null && it.configFile.path == path } ?: locator.selectedConfig()
        }

        /**
         * A clone of [configuration] whose CLI options end with [RunPatch.cliArgs] and whose env data includes
         * [RunPatch.env], or null when the settings API is not what we expect.
         */
        private fun withPatch(configuration: RunConfiguration, patch: RunPatch): RunConfiguration? = try {
            val settings = settingsOf(configuration)
            val cliOptions = (settings.javaClass.getMethod("getCliOptions").invoke(settings) as String?).orEmpty().trim()
            val builder = settings.javaClass.getMethod("builder").invoke(settings)
            if (patch.cliArgs.isNotEmpty()) {
                val added = ParametersListUtil.join(patch.cliArgs)
                val newOptions = listOf(cliOptions, added).filter { it.isNotEmpty() }.joinToString(" ")
                builder.javaClass.getMethod("setCliOptions", String::class.java).invoke(builder, newOptions)
            }
            if (patch.env.isNotEmpty()) {
                val envData = settings.javaClass.getMethod("getEnvData").invoke(settings) as? EnvironmentVariablesData
                    ?: EnvironmentVariablesData.DEFAULT
                val merged = envData.with(envData.envs + patch.env)
                builder.javaClass.getMethod("setEnvData", EnvironmentVariablesData::class.java).invoke(builder, merged)
            }
            val newSettings = builder.javaClass.getMethod("build").invoke(builder)
            val copy = configuration.clone()
            val setter = copy.javaClass.methods.first {
                it.name == "setSettings" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(newSettings)
            }
            setter.invoke(copy, newSettings)
            copy
        } catch (e: Exception) {
            LOG.warn("Cannot patch Playwright run configuration '${configuration.name}'", e)
            null
        }
    }
}
