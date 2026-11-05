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

/** Project-level settings. The LLM API key is kept in [PasswordSafe], never in this state. */
@Service(Service.Level.PROJECT)
@State(name = "PlaywrightSettings", storages = [Storage("playwright.xml")])
class PwSettings : SimplePersistentStateComponent<PwSettings.State>(State()) {

    class State : BaseState() {
        /** Path to a node executable; empty means the IDE's configured Node interpreter. */
        var nodeInterpreterPath by string()
        var defaultRecordUrl by string()
        /** User override; null means "use the config's use.testIdAttribute, else data-testid". */
        var testIdAttribute by string()
        var alwaysRecordTrace by property(false)
        /** Add --headed to Playwright runs started from the IDE. */
        var showBrowser by property(false)
        /** One of [AgentBackendId] names. */
        var agentBackend by string(AgentBackendId.CLAUDE.name)
        var agentCliPath by string()
        var selectedConfigPath by string()
        var lastInitAgentsVersion by string()
        var lastInitAgentsLoop by string()
        var codexSkipGitCheck by property(false)
    }

    /** The test id attribute the user set in settings, or null. Resolve the effective value with PlaywrightProjectLocator.testIdAttribute. */
    val testIdAttributeOverride: String?
        get() = state.testIdAttribute?.trim()?.takeIf { it.isNotEmpty() }

    fun setShowBrowser(enabled: Boolean) {
        state.showBrowser = enabled
    }

    /** Reads the API key. Call off the EDT. */
    fun apiKey(): String? = PasswordSafe.instance.getPassword(API_KEY_ATTRIBUTES)

    /** Stores the API key; null or blank removes it. */
    fun setApiKey(key: String?) {
        val credentials = key?.takeIf { it.isNotBlank() }?.let { Credentials("api-key", it) }
        PasswordSafe.instance.set(API_KEY_ATTRIBUTES, credentials)
    }

    companion object {
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
