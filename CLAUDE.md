# Playwright for IntelliJ

Kotlin plugin for IntelliJ IDEA Ultimate and WebStorm (2026.1+, since-build 261) that brings the
features of Microsoft's Playwright Test for VS Code extension, plus the Playwright test agents.
Depends on the bundled `JavaScript` plugin. Targets TypeScript/JavaScript Playwright Test projects,
Playwright 1.56 or newer.

The implementation plan lives in `docs/PLAN.md`.

## Hard rules

- NEVER run tests, `runIde`, `verifyPlugin`, `check`, or `./gradlew build`.
- NEVER write test code. NEVER create a workflow, agent, or script whose purpose is to test or verify behavior.
  The owner does all testing.
- The only allowed verification is `./gradlew compileKotlin` and `./gradlew buildPlugin`.
- No AI attribution anywhere: no `Co-Authored-By`, no `Claude-Session`, no "Generated with" lines in commits or PRs.
- Push and merge freely; never force-push, rebase, or rewrite history. Never publish. Never touch signing keys or tokens.
- Always write complete files, never partial snippets.
- Files ported from playwright-vscode keep Microsoft's copyright header, with ours added below it,
  and are listed in `NOTICE`.
- Debug controller protocol message shapes live only in `src/main/kotlin/dev/uzi/pw/core/Protocol.kt`.
- Read the playwright-vscode / playwright source before porting. Do not rely on memory for APIs or message shapes.
- Local commands for the owner are PowerShell (Windows).

## Commit convention

Semantic commit messages: `label(scope): description`. Labels: `fix`, `feat`, `chore`, `docs`, `devops`.
Enable the hook that strips AI trailers: `git config core.hooksPath .githooks`.

## Layout

```
src/main/kotlin/dev/uzi/pw/
  core/      PlaywrightProjectLocator NodeRunner DebugControllerClient Protocol PwModel PwStartupActivity PwTopics PwNotifications
  recorder/  RecorderService RecordNewAction RecordAtCursorAction InsertionTracker NewTestFile BootstrapRunner
  locator/   LocatorPicker PickLocatorAction HighlightLocatorAction
  trace/     TraceService TraceWatcher TraceViewerPanel TraceIndex TraceProgramRunner
  agents/    AgentService AgentBackend ClaudeCliBackend CodexCliBackend OpencodeCliBackend InitAgentsAction Plan/Generate/Heal actions
  ui/        PlaywrightToolWindowFactory RecordTab TracesTab AgentsTab
  settings/  PwSettings PwConfigurable
```

## Reference sources

- microsoft/playwright-vscode: `src/reusedBrowser.ts`, `src/backend.ts`, `src/spawnTraceViewer.ts`, `src/playwrightFinder.ts`
- microsoft/playwright: `packages/protocol/spec/playwright.yml` (DebugController), `packages/playwright-core/src/server/debugController.ts`,
  `packages/playwright/src/program.ts` (`init-agents`, `run-test-mcp-server`), `packages/playwright/src/agents/generateAgents.ts`
- IntelliJ SDK docs: https://plugins.jetbrains.com/docs/intellij/
