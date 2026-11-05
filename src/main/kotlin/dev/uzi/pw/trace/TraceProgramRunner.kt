/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.trace

/*
 * "Always record trace" hook for the bundled Playwright run configuration.
 *
 * What javap showed in IDEA 2026.1.5 (261.27258.48), plugins/javascript-plugin/lib/javascript-plugin.jar:
 *
 * - Run configuration: com.intellij.aqua.runners.playwright.js.PlaywrightRunConfiguration (final)
 *     extends com.intellij.aqua.runners.js.core.JsTestRunConfiguration<PlaywrightRunSettings>
 *     extends com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile.
 *   Type: PlaywrightConfigurationType, id "JavaScriptTestRunnerPlaywright".
 *   Public: `PlaywrightRunSettings getSettings()`, `void setSettings(PlaywrightRunSettings)` (plus bridges taking
 *   JsTestRunSettings). The settings field is read once in the PlaywrightRunProfileState constructor, i.e. inside
 *   `getState()`.
 *   It lives in the JavaScript plugin's content module `intellij.aqua.runners.playwright.js`, declared
 *   `visibility="internal"` (module `intellij.aqua.runners.js.core` likewise). Linking against those classes from
 *   another plugin is not reliable, so everything below goes through the run configuration type id and reflection.
 *
 * - Settings: abstract JsTestRunSettings<S> has public getters `getInterpreterRef, getNodeOptions,
 *   getFrameworkPackage, getConfigFilePath, getWorkingDir, getCliOptions, getEnvData, getScope` and
 *   `abstract JsTestRunSettingsBuilder<S> builder()`. JsTestRunSettingsBuilder<S> has public setters for all of
 *   them (`setCliOptions(String)`, `setNodeOptions(String)`, `setEnvData(EnvironmentVariablesData)`, ...) and
 *   `abstract S build()`. PlaywrightRunSettings.Builder extends it.
 *
 * - Command line: PlaywrightRunProfileState.configureCommandLine adds node options (`addNodeOptionsWithExpandedMacros`),
 *   `<playwright cli>`, `test`, `-c <config>`, the test paths / `-g` pattern, and then
 *   `ParametersListUtil.parse(settings.getCliOptions())`. So `--trace on` in the CLI options reaches
 *   `playwright test` as a normal argument.
 *
 * - JavaScript.nodeRunConfigurationExtension: EP declared in the JavaScript plugin.xml as
 *   `<extensionPoint qualifiedName="JavaScript.nodeRunConfigurationExtension"
 *    interface="com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension"
 *    dynamic="true"/>`.
 *   AbstractNodeRunConfigurationExtension extends RunConfigurationExtensionBase<AbstractNodeTargetRunProfile>;
 *   it has no abstract methods of its own (open: `createEditor`, `getEditorTitle`, `shouldExtendMainEditor`,
 *   `checkConfiguration`, `createLaunchSession(profile, env)`; `isApplicableFor` comes from
 *   RunConfigurationExtensionBase). The extension manager (NodeRunConfigurationExtensionsManager) is only used by
 *   AbstractNodeTargetRunProfile for the editor and read/writeExternal. `createLaunchSession` is called only by
 *   NodeJsRunProfileState (Node.js configs), NpmRunProfileState and Karma. PlaywrightRunProfileState never calls the
 *   manager, so a node run-config extension (and a `--require` preload) never runs for Playwright tests.
 *   That rules out the preload approach; no `pw-trace-preload.cjs` is needed.
 *
 * - Ordering in the platform (ExecutionManagerImpl / GenericProgramRunner / AsyncProgramRunner):
 *   before-run tasks -> `ProgramRunner.execute(env)` -> `env.getState()` -> `startRunProfile` ->
 *   `ExecutionListener.processStartScheduled`. A listener is too late, because the state has already captured the
 *   settings.
 *
 * Chosen approach: a ProgramRunner registered `order="first"` that claims Playwright configurations only while
 * PwSettings.alwaysRecordTrace is on and some other runner can run them. Its `execute` clones the configuration,
 * sets the clone's CLI options to `<user options> --trace on` through the public settings builder, and hands an
 * environment for the clone to the runner that would have run it otherwise. The user's configuration is never
 * modified. When the user's CLI options already contain `--trace`, nothing is added.
 */

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.execution.ParametersListUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.settings.PwSettings

/** Id of PlaywrightConfigurationType in the bundled JavaScript plugin. */
internal const val PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID: String = "JavaScriptTestRunnerPlaywright"

/** Adds `--trace on` to every Playwright run while "Always record trace" is on. See the notes at the top of the file. */
class TraceProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (!isPlaywright(profile)) return false
        val project = (profile as RunConfiguration).project
        if (project.isDisposed || !PwSettings.getInstance(project).state.alwaysRecordTrace) return false
        return delegateFor(executorId, profile) != null
    }

    @Throws(ExecutionException::class)
    override fun execute(environment: ExecutionEnvironment) {
        val profile = environment.runProfile
        val executorId = environment.executor.id
        val delegate = delegateFor(executorId, profile)
            ?: throw ExecutionException(PwBundle.message("trace.error.noRunner", profile.name))

        val traced = (profile as? RunConfiguration)?.let { withTraceOn(it) }
        val builder = ExecutionEnvironmentBuilder(environment)
        val settings = environment.runnerAndConfigurationSettings
        if (settings != null) builder.runnerAndSettings(delegate, settings) else builder.runner(delegate)
        builder.runProfile(traced ?: profile)
            .executionId(environment.executionId)
        environment.dataContext?.let { builder.dataContext(it) }
        val newEnvironment = builder.build(environment.callback)
        delegate.execute(newEnvironment)
    }

    companion object {
        private val LOG = logger<TraceProgramRunner>()
        const val RUNNER_ID: String = "dev.uzi.pw.traceRunner"

        private fun isPlaywright(profile: RunProfile): Boolean =
            profile is RunConfiguration && profile.type.id == PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID

        private fun delegateFor(executorId: String, profile: RunProfile): ProgramRunner<*>? =
            ProgramRunner.PROGRAM_RUNNER_EP.extensionList.firstOrNull {
                it !is TraceProgramRunner && it.canRun(executorId, profile)
            }

        /**
         * A clone of [configuration] whose CLI options end with `--trace on`, or null when the options already set
         * `--trace` or the settings API is not what we expect.
         */
        private fun withTraceOn(configuration: RunConfiguration): RunConfiguration? = try {
            val settings = configuration.javaClass.getMethod("getSettings").invoke(configuration)
            val cliOptions = settings.javaClass.getMethod("getCliOptions").invoke(settings) as String?
            val args = ParametersListUtil.parse(cliOptions.orEmpty())
            if (args.any { it == "--trace" || it.startsWith("--trace=") }) {
                null
            } else {
                val builder = settings.javaClass.getMethod("builder").invoke(settings)
                val newOptions = listOf(cliOptions.orEmpty().trim(), "--trace on").filter { it.isNotEmpty() }.joinToString(" ")
                builder.javaClass.getMethod("setCliOptions", String::class.java).invoke(builder, newOptions)
                val newSettings = builder.javaClass.getMethod("build").invoke(builder)
                val copy = configuration.clone()
                val setter = copy.javaClass.methods.first {
                    it.name == "setSettings" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(newSettings)
                }
                setter.invoke(copy, newSettings)
                copy
            }
        } catch (e: Exception) {
            LOG.warn("Cannot add --trace on to Playwright run configuration '${configuration.name}'", e)
            null
        }
    }
}
