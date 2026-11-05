/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

/**
 * The three Playwright test agents written by `playwright init-agents`.
 * [agentName] is the shared agent name from `generateAgents.ts`; Codex uses its snake_case form
 * (`CodexGenerator.codexName`).
 */
enum class AgentKind(val agentName: String) {
    PLANNER("playwright-test-planner"),
    GENERATOR("playwright-test-generator"),
    HEALER("playwright-test-healer");

    /** `playwright_test_planner` etc., the name Codex uses for the agent file and the `name` field. */
    val codexName: String get() = agentName.replace('-', '_')
}
