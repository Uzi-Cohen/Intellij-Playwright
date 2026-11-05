/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.run

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import dev.uzi.pw.core.PwConfig

/** Extra `playwright test` CLI args and environment variables for one run. */
data class RunPatch(val cliArgs: List<String> = emptyList(), val env: Map<String, String> = emptyMap()) {
    val isEmpty: Boolean get() = cliArgs.isEmpty() && env.isEmpty()

    operator fun plus(other: RunPatch) = RunPatch(cliArgs + other.cliArgs, env + other.env)
}

/** What a [RunPatchContributor] sees about the run it may patch. */
class RunPatchContext(
    val project: Project,
    val configuration: RunConfiguration,
    val executorId: String,
    /** The CLI options already set in the run configuration, split into arguments. */
    val userCliArgs: List<String>,
    /** The plugin's view of the Playwright config this run uses, when it can be matched. */
    val pwConfig: PwConfig?,
) {
    fun userHasOption(vararg names: String): Boolean =
        userCliArgs.any { arg -> names.any { arg == it || arg.startsWith("$it=") } }
}

/**
 * One feature that changes Playwright test runs started from the IDE (trace, headed, reuse browser).
 * All contributors are applied by the single [PlaywrightRunPatcher].
 */
interface RunPatchContributor {
    /** Cheap check, used to decide whether the patcher wraps the run at all. */
    fun isActive(project: Project): Boolean

    /** The args/env to add; null or empty for none. May start background work (e.g. a browser). */
    suspend fun patch(context: RunPatchContext): RunPatch?

    /** The patched run's process started. */
    fun onRunStarted(context: RunPatchContext) {}

    /** The patched run's process ended (or never started). */
    fun onRunFinished(context: RunPatchContext) {}
}
