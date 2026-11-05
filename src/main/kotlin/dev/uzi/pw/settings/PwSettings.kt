/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.uzi.pw.core.PwTopics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Project-level settings. The LLM API key is kept in [PasswordSafe], never in this state. */
@Service(Service.Level.PROJECT)
@State(name = "PlaywrightSettings", storages = [Storage("playwright.xml")])
class PwSettings(private val project: Project) : SimplePersistentStateComponent<PwSettings.State>(State()) {

    class State : BaseState() {
        /** Path to a node executable; empty means the IDE's configured Node interpreter. */
        var nodeInterpreterPath by string()
        var defaultRecordUrl by string()
        /** User override; null means "use the config's use.testIdAttribute, else data-testid". */
        var testIdAttribute by string()
        var alwaysRecordTrace by property(false)
        /** Add --headed to Playwright runs started from the IDE. */
        var showBrowser by property(false)
        /** Run tests in the plugin's browser and keep it open between runs. Effective only with [showBrowser]. */
        var reuseBrowser by property(false)
        /** Show the Reports tab with the latest report when a Playwright run ends. */
        var openReportAfterRun by property(false)
        /** Clear the allure-playwright results folder before each Playwright run started from the IDE. */
        var cleanAllureResults by property(true)
        /** One of [AgentBackendId] names. */
        var agentBackend by string(AgentBackendId.CLAUDE.name)
        var agentCliPath by string()
        var selectedConfigPath by string()
        var lastInitAgentsVersion by string()
        var lastInitAgentsLoop by string()
        var codexSkipGitCheck by property(false)
        /** Environment variables for every Playwright process the plugin starts, as a JSON object (playwright-vscode `playwright.env`). */
        var envVarsJson by string()
        /** Optional dotenv file (absolute, or relative to the config's working directory). */
        var envFile by string()
        /** Also use the env vars and env file of the project's Playwright run configurations. */
        var envFromRunConfigurations by property(true)
    }

    /** The test id attribute the user set in settings, or null. Resolve the effective value with PlaywrightProjectLocator.testIdAttribute. */
    val testIdAttributeOverride: String?
        get() = state.testIdAttribute?.trim()?.takeIf { it.isNotEmpty() }

    /** Reuse browser is on and allowed (it requires Show browser, as in playwright-vscode where both are one setting). */
    val isReuseBrowserEffective: Boolean
        get() = state.showBrowser && state.reuseBrowser

    fun envVars(): Map<String, String> {
        val json = state.envVarsJson?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return try {
            Json.parseToJsonElement(json).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setEnvVars(vars: Map<String, String>) {
        state.envVarsJson = if (vars.isEmpty()) null else JsonObject(vars.mapValues { JsonPrimitive(it.value) }).toString()
    }

    fun setShowBrowser(enabled: Boolean) {
        if (state.showBrowser == enabled) return
        state.showBrowser = enabled
        changed()
    }

    /**
     * Reuse browser and "always record trace" exclude each other, like playwright-vscode's showBrowser/showTrace
     * (src/settingsModel.ts): a reused context's tracing breaks when test-results is cleaned between runs.
     */
    fun setReuseBrowser(enabled: Boolean) {
        if (state.reuseBrowser == enabled) return
        state.reuseBrowser = enabled
        if (enabled) state.alwaysRecordTrace = false
        changed()
    }

    fun setAlwaysRecordTrace(enabled: Boolean) {
        if (state.alwaysRecordTrace == enabled) return
        state.alwaysRecordTrace = enabled
        if (enabled) state.reuseBrowser = false
        changed()
    }

    private fun changed() {
        if (!project.isDisposed) project.messageBus.syncPublisher(PwTopics.SETTINGS_CHANGED).settingsChanged()
    }

    /** Reads the API key. Call off the EDT. */
    fun apiKey(): String? = PasswordSafe.instance.getPassword(API_KEY_ATTRIBUTES)

    /** Stores the API key; null or blank removes it. */
    fun setApiKey(key: String?) {
        val credentials = key?.takeIf { it.isNotBlank() }?.let { Credentials("api-key", it) }
        PasswordSafe.instance.set(API_KEY_ATTRIBUTES, credentials)
    }

    companion object {
        // Keep this service name: renaming it would orphan API keys already stored under it.
        private val API_KEY_ATTRIBUTES = CredentialAttributes(generateServiceName("Playwright for IntelliJ", "llm-api-key"))

        fun getInstance(project: Project): PwSettings = project.service()
    }
}

enum class AgentBackendId(val displayName: String) {
    CLAUDE("Claude Code"),
    CODEX("Codex"),
    OPENCODE("OpenCode"),
    MCP_DIRECT("Direct MCP (not available yet)"),
}
