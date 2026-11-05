# Playwright for IntelliJ: Implementation Plan

Status: plan only. No code written yet. Everything below was checked against the sources named next to it on 2026-11-05.

Reference snapshots used:
- `uzi-cohen/playwright-vscode` @ `27fa358` (2026-11-03), extension version 1.1.20
- `uzi-cohen/playwright` @ `7135071` (main, `playwright-core` 1.66.0-next), plus `microsoft/playwright` tags `v1.56.0` to `v1.64.0` fetched raw from GitHub
- `uzi-cohen/intellij-sdk-docs` @ `f1966f4` (2026-11-05)
- JetBrains `intellij-platform-plugin-template` main (raw files)
- JetBrains release feed `data.services.jetbrains.com`, plugins.gradle.org, Maven Central metadata
- Official CLI docs: code.claude.com (cli-reference, settings-reference), developers.openai.com/codex (noninteractive, cli reference, subagents), opencode.ai/docs (cli, agents)

---

## 1. Verified versions

| Item | Value | Source |
|---|---|---|
| Playwright minimum | **1.56.0** | Requirement. Debug-controller protocol in `v1.56.0/packages/protocol/src/protocol.yml` is byte-for-byte the same as `main/packages/protocol/spec/playwright.yml` for the `DebugController` interface |
| Codex agent loop | **Playwright ≥ 1.61.0** | `init-agents --loop` choices: 1.56 = `vscode, claude, opencode`; 1.57–1.60 add `copilot, vscode-legacy`; **1.61** adds `codex` (checked tag by tag) |
| IDE latest release | IntelliJ IDEA 2026.2.4 (262.11879.100), WebStorm 2026.2.4 (262.11879.105), 2026-10-30 | JetBrains release feed |
| **Compile target** | **IntelliJ IDEA 2026.1.5** (`intellijIdea("2026.1.5")`, build 261.27258.48) | Release feed. Decision below |
| **since-build** | **261** (no until-build) | Covers 2026.1.x and 2026.2.x of IDEA and WebStorm |
| IntelliJ Platform Gradle Plugin | **2.20.0** (`org.jetbrains.intellij.platform` and `org.jetbrains.intellij.platform.settings`) | plugins.gradle.org `<release>2.20.0`; SDK docs `v.list` `intellij-platform-gradle-plugin-version=2.20.0` (template still says 2.19.0) |
| Kotlin Gradle plugin | **2.4.21** (latest stable; 2.5.0 is still Beta2) | plugins.gradle.org metadata; template `settings.gradle.kts` |
| Kotlin api/language version | **2.3** | SDK docs `using_kotlin.md`: 2026.1 bundles stdlib 2.3.20; a plugin that supports several platform versions must target the lowest bundled stdlib |
| kotlinx.serialization | Gradle plugin `org.jetbrains.kotlin.plugin.serialization` 2.4.21; runtime **not bundled**, the platform's 1.9.0 is used | SDK docs `configuring_split_mode.md` library table |
| kotlinx.coroutines | Platform's bundled copy only | SDK docs `using_kotlin.md` ("Use Bundled Library" warning) |
| Gradle wrapper | **9.8.1** | template `gradle-wrapper.properties` |
| Other template plugins | `org.jetbrains.changelog` 2.5.0, `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 | template `settings.gradle.kts` |
| JDK | **21** (`kotlin { jvmToolchain(21) }`) | SDK docs `build_number_ranges.md`: 2026.1 runs on Java 21; 2026.2 runs on Java 25 and loads Java 21 bytecode. The template's CI also builds with Java 21. Sandbox has JDK 21.0.12 |
| Kotlin MCP SDK (backend B) | **0.16.0**, artifact `io.modelcontextprotocol:kotlin-sdk-client` | Maven Central metadata (`lastUpdated 20261027`), kotlin-sdk `gradle.properties` on main |

**Decision: compile against 2026.1.5, not 2026.2.4.** Building against the oldest supported platform stops 262-only APIs from creeping in, and it keeps the toolchain at Java 21 everywhere. The owner can still run it in 2026.2.x. `pluginVerification.ides` lists 2026.1.5 and 2026.2.4 so the owner can check both. (I never run `verifyPlugin`.)

---

## 2. Places where the brief does not match the source (decided)

1. **There is no `navigate` and no `resetForReuse` in the debug controller.** In 1.56 and on main, the only commands are `initialize, setReportStateChanged, setRecorderMode, highlight, hideHighlight, resume, kill`. `DebugControllerClient` exposes exactly those. Opening a URL is done the way playwright-vscode does it (section 4.3).
2. **`setRecorderMode` accepts only `inspecting | recording | none`.** playwright-vscode also sends `standby` in `resetRecorderModeNoWait`. That call fails protocol validation and the extension swallows the error. We never send `standby`. We do receive it as a `string` in `setModeRequested`.
3. **`--loop=codex` needs Playwright 1.61+.** On 1.56–1.60 the Codex backend is greyed out with the reason shown.
4. **No `--trace on` hook is documented for Playwright run configurations.** See section 5 for the hook, how it's checked, and the fallback chain.
5. **`show-trace --port` always opens the system browser** (`openTraceInBrowser` calls `open(url)` unless `PWTEST_UNDER_TEST` is set; on main also unless `CLAUDECODE`/`COPILOT_CLI` are set). We set `PWTEST_UNDER_TEST=1` on that process only, so the URL goes only to JCEF. If a later Playwright drops that check, the worst case is an extra browser tab.
6. **The template's CI runs `check`, `verifyPlugin` and UI tests, and `release.yml` publishes with signing secrets.** Both break the hard rules. We keep only the `build` job of `build.yml` (`./gradlew buildPlugin` plus artifact upload). We drop the `test`, `verify` and `releaseDraft` jobs, `release.yml`, and `run-ui-tests.yml`.
7. **The playwright-vscode recorder names files `test-N.spec.ts`.** We use `recorded-N.spec.ts` as the brief says.

---

## 3. Step 0: repository setup (full detail)

Repo: `uzi-cohen/Intellij-Playwright`, branch `claude/playwright-intellij-plan-s0il4s`. The repo root is the `playwright-intellij/` tree from the brief. Commits stay local; nothing is pushed.

### Files

| File | Content |
|---|---|
| `settings.gradle.kts` | `rootProject.name = "playwright-intellij"`. `pluginManagement.plugins`: `org.jetbrains.kotlin.jvm` 2.4.21, `org.jetbrains.kotlin.plugin.serialization` 2.4.21, `org.jetbrains.changelog` 2.5.0. `plugins`: `foojay-resolver-convention` 1.0.0, `org.jetbrains.intellij.platform.settings` 2.20.0. `dependencyResolutionManagement.repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }` |
| `build.gradle.kts` | Plugins: `kotlin.jvm`, `kotlin.plugin.serialization`, `org.jetbrains.intellij.platform` (2.20.0 via settings), `org.jetbrains.changelog`. `kotlin { jvmToolchain(21); compilerOptions { apiVersion.set(KOTLIN_2_3); languageVersion.set(KOTLIN_2_3) } }`. Dependencies: `intellijPlatform { intellijIdea(providers.gradleProperty("platformVersion")); bundledPlugin("JavaScript") }`. **No `testFramework`, no `testImplementation`.** `intellijPlatform { pluginConfiguration { id, name, version, ideaVersion { sinceBuild = "261"; untilBuild = provider { null } }, description from README via changelog plugin }; pluginVerification { ides { create(IntelliJPlatformType.IntellijIdea, "2026.1.5"); create(IntelliJPlatformType.IntellijIdea, "2026.2.4"); create(IntelliJPlatformType.WebStorm, "2026.2.4") } } }`. No `signing {}` and no `publishing {}` blocks |
| `gradle.properties` | `pluginGroup = dev.uzi.pw`, `pluginName = Playwright for IntelliJ`, `pluginVersion = 0.1.0`, `platformVersion = 2026.1.5`, `kotlin.stdlib.default.dependency = false`, `org.gradle.configuration-cache = true`, `org.gradle.caching = true` |
| `gradle/wrapper/*`, `gradlew`, `gradlew.bat` | Copied unchanged from the template (Gradle 9.8.1) |
| `src/main/resources/META-INF/plugin.xml` | Step 1 fills it in. In step 0 it holds only id/name/vendor plus `<depends>com.intellij.modules.platform</depends>` and `<depends>JavaScript</depends>`. The SDK docs (webstorm.md) require both, otherwise WebStorm treats the plugin as legacy |
| `src/main/resources/messages/PwBundle.properties` | UI strings |
| `src/main/kotlin/dev/uzi/pw/PwBundle.kt` | `DynamicBundle("messages.PwBundle")` |
| `LICENSE` | Apache License 2.0, full text |
| `NOTICE` | "Playwright for IntelliJ, Copyright 2026 <owner>." Then: "This product includes software derived from Playwright Test for VS Code (https://github.com/microsoft/playwright-vscode), Copyright (c) Microsoft Corporation, licensed under the Apache License 2.0." Then a list of ported files (filled in as each is ported): `core/Protocol.kt` ← `src/reusedBrowser.ts`, `src/backend.ts`; `core/DebugControllerClient.kt` ← `src/backend.ts`, `src/reusedBrowser.ts`; `recorder/InsertionTracker.kt` ← `src/reusedBrowser.ts` (`guessIndentation`, `indentBlock`); `trace/TraceService.kt` ← `src/spawnTraceViewer.ts`; `core/PlaywrightProjectLocator.kt` ← `src/playwrightFinder.ts`. Also: "Protocol shapes taken from microsoft/playwright `packages/protocol/src/protocol.yml` v1.56.0, Apache 2.0" |
| `README.md` | What it is, requirements (IDEA Ultimate/WebStorm 2026.1+, Playwright ≥1.56, Node), features, and local build commands in PowerShell |
| `CHANGELOG.md` | Keep-a-changelog stub (the changelog plugin needs it) |
| `CLAUDE.md` | Project rules: the hard rules from the brief, written out (no tests/runIde/verifyPlugin/check/build; only `compileKotlin` and `buildPlugin`; never push or publish; no AI attribution; complete files only; header rule for ported files; protocol shapes live only in `core/Protocol.kt`; PowerShell for local commands). Semantic commit format `label(scope): description` with labels `fix feat chore docs devops` (no `test`). Package layout. "Read playwright-vscode/playwright source before porting, never rely on memory" |
| `.claude/settings.json` | `{"attribution": {"commit": "", "pr": "", "sessionUrl": false}, "includeCoAuthoredBy": false, "permissions": {"allow": ["Bash(./gradlew compileKotlin*)", "Bash(./gradlew buildPlugin*)", "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)", "Bash(git add*)", "Bash(git commit*)"], "deny": ["Bash(git push*)", "Bash(./gradlew test*)", "Bash(./gradlew check*)", "Bash(./gradlew runIde*)", "Bash(./gradlew verifyPlugin*)", "Bash(./gradlew build)", "Bash(./gradlew build *)", "Bash(./gradlew publishPlugin*)", "Bash(./gradlew signPlugin*)"]}}`. The empty-string form of `attribution` is what settings-reference recommends for files that older Claude Code versions also read. `includeCoAuthoredBy` is kept as a deprecated belt-and-braces setting |
| `.claude/commands/port.md` | `/port <vscode-file> <target.kt>` slash command. It reads the named source in `../playwright-vscode/src`, writes the complete Kotlin file with Microsoft's Apache header first and ours below it, adds the file to `NOTICE`, runs `./gradlew compileKotlin` only, and never writes tests |
| `.githooks/commit-msg` | POSIX `sh` script (Git for Windows runs it with its bundled sh). It deletes lines matching `^(Co-Authored-By|Co-authored-by):.*(Claude\|anthropic)`, `^Claude-Session:`, and lines containing `Generated with [Claude Code]` or `🤖 Generated with`. Then it trims trailing blank lines and writes the result back to `$1`. Enable it once with `git config core.hooksPath .githooks` (in the README) |
| `.github/workflows/build.yml` | The template's `build` job only: checkout, setup-java zulu 21, setup-gradle, `./gradlew buildPlugin`, upload `build/distributions/*.zip` |
| `.github/dependabot.yml` | From the template (gradle and github-actions) |
| `.gitignore` | Template entries (`.gradle`, `build`, `.idea`, `.intellijPlatform`, `.kotlin`) plus `.claude/settings.local.json`, `test-results/`, `playwright-report/`, `node_modules/` |

Compile check after step 0: `./gradlew compileKotlin` (only `PwBundle.kt` exists). The first run also downloads IDEA 2026.1.5 into the Gradle cache, which step 1 needs.

**API confirmation right after step 0 (read-only):** use `unzip -l` and `javap -cp <jar>` on the cached `plugins/javascript-plugin/lib/*.jar` to confirm the exact FQNs and signatures step 1 relies on: `NodeJsInterpreterManager`, `NodeJsLocalInterpreter`, `NodeJsInterpreterRef`, the JS PSI classes (`JSCallExpression`, `JSFunctionExpression`, `JSArgumentList`, `JSLiteralExpression`, `JSReferenceExpression`), `AbstractNodeRunConfigurationExtension`, and the Playwright run-configuration class (section 5). If any FQN differs from what's written below, the decision is updated here before code is written.

---

## 4. Step 1: core layer and tool window shell (full detail)

Depends on step 0. Each class below is one complete file.

### 4.1 `settings/PwSettings.kt`

- `@Service(Service.Level.PROJECT) @State(name = "PlaywrightSettings", storages = [Storage("playwright.xml")]) class PwSettings : SimplePersistentStateComponent<PwSettings.State>(State())`
- `class State : BaseState()` fields: `nodeInterpreterPath: String?` (empty means use the IDE interpreter), `defaultRecordUrl: String?` (default `""`), `testIdAttribute: String?` (default `"data-testid"`), `alwaysRecordTrace: Boolean` (false), `agentBackend: String` (`"CLAUDE"`, also `CODEX`, `OPENCODE`, `MCP_DIRECT`), `agentCliPath: String?`, `selectedConfigPath: String?`, `lastInitAgentsVersion: String?`, `lastInitAgentsLoop: String?`
- API key: **not** in `State`. `apiKey(): String?` and `setApiKey(String?)` use `PasswordSafe.instance` with `CredentialAttributes(generateServiceName("Playwright for IntelliJ", "llm-api-key"))` (SDK docs `persisting_sensitive_data.md`). Reads happen off the EDT.
- Extension point: `com.intellij.projectService` is implied by `@Service` (light service), so no plugin.xml entry is needed.

### 4.2 `settings/PwConfigurable.kt`

- `class PwConfigurable(private val project: Project) : BoundConfigurable(PwBundle.message("settings.title"))` built with Kotlin UI DSL v2 (`panel {}`).
- Rows: Node interpreter override (`textFieldWithBrowseButton`, files), default record URL, test id attribute, "Always record trace" checkbox, agent backend combo (Claude / Codex / OpenCode / Direct MCP (later)), agent CLI path (`textFieldWithBrowseButton`), API key (`passwordField`, saved through PasswordSafe in `apply()`), and a read-only label with the detected Playwright version for each config.
- Registered with `<projectConfigurable parentId="tools" instance="dev.uzi.pw.settings.PwConfigurable" id="dev.uzi.pw.settings" key="settings.title" bundle="messages.PwBundle"/>`.

### 4.3 `core/PlaywrightProjectLocator.kt` (project service)

- `@Service(PROJECT) class PlaywrightProjectLocator(private val project: Project, private val cs: CoroutineScope)`. The service coroutine scope is injected, as documented in `kotlin_coroutines.md`.
- `data class PwConfig(val configFile: VirtualFile, val workingDir: VirtualFile, val packageDir: Path?, val cliJs: Path?, val version: PwVersion?, val packageName: String)`
- `data class PwVersion(major, minor, patch, raw)` with `>=`. Parsing strips `-next`/`-beta` suffixes, as `playwrightFinder.ts` does.
- `data class PwProjectInfo(val name: String, val testDir: Path, val outputDir: Path)`
- **Finding configs:** under `readAction {}`, `FilenameIndex.getVirtualFilesByName` for `playwright.config.ts`, `playwright.config.js`, `playwright.config.mjs` (and `.mts`, `.cjs`, `.cts`, since Playwright accepts those too), in `GlobalSearchScope.projectScope(project)`, skipping anything under `node_modules`. The result is cached and refreshed by a VFS listener (4.10) when a file with one of those names is created, deleted or moved.
- **Resolving the package:** this ports `playwrightFinder.ts`, but without running Node. Walk up from the config's directory looking for `node_modules/@playwright/test/package.json`, then `node_modules/playwright/package.json` (same order as the vscode finder; component-testing packages are skipped since they're out of scope). `cliJs = <pkgDir>/cli.js`. Read `version` from package.json with kotlinx.serialization `Json.parseToJsonElement`.
- **Version gate:** below 1.56 (or not found), show one notification per config in group `Playwright`: "Playwright 1.56+ is required, found X" with actions "Update @playwright/test", which opens a terminal running the npm command (it uses `TerminalToolWindowManager` only if the bundled terminal plugin exists; otherwise it copies the command to the clipboard), and "Open package.json". Features check `config.isSupported` and stay disabled when it's false.
- **Project info (testDir/outputDir):** `suspend fun projects(config): List<PwProjectInfo>` runs `NodeRunner` with `cli.js test --list --reporter=json -c <config>` in `workingDir` and parses `config.projects[].{name,testDir,outputDir}` from the JSON report (checked in `reporters/json.ts`: those three fields are always serialized). The result is cached per config and invalidated when the config file changes. `testIdAttribute` is **not** in the JSON report (`use` is stripped), so the setting is the source of truth.
- `selectedConfig(): PwConfig?` comes from `PwSettings.selectedConfigPath`, falling back to the first one found. `MessageBus` topic `PwTopics.CONFIGS_CHANGED` tells the tool window when the list changes.
- `ProjectActivity` (4.9) triggers the first scan.

### 4.4 `core/NodeRunner.kt` (project service)

- `@Service(PROJECT) class NodeRunner(project, cs)`
- `fun nodePath(): String?`: if `PwSettings.nodeInterpreterPath` is set, use it. Otherwise `NodeJsInterpreterManager.getInstance(project).interpreter`, cast to `NodeJsLocalInterpreter`, and take `interpreterSystemDependentPath`. A remote/WSL interpreter gets a notification that it isn't supported in 0.1; the user can set a local override. (Exact names are confirmed with javap after step 0.)
- `fun commandLine(cfg: PwConfig, vararg args: String, env: Map<String,String> = emptyMap()): GeneralCommandLine` builds `GeneralCommandLine(node, cfg.cliJs.toString(), *args)` with `withWorkDirectory(cfg.workingDir.path)`, `withParentEnvironmentType(CONSOLE)`, `withEnvironment(env)`, `withCharset(UTF_8)`. **Never npx.**
- `fun start(cmd, console: ConsoleView?, listener: ProcessListener? = null): KillableColoredProcessHandler` creates `KillableColoredProcessHandler(cmd)` (an OSProcessHandler subclass with ANSI colouring and soft-kill), attaches the console (`console.attachToProcess(handler)`), adds the listener, and calls `startNotify()`.
- `suspend fun runCapture(cmd, timeout): ProcessOutput` uses `CapturingProcessHandler(cmd).runProcess(timeoutMs)` inside `withContext(Dispatchers.IO)`. It never runs on the EDT or under a read lock (2026.1 notable change: `waitFor()` on EDT/read lock now logs an error).
- `fun createConsole(): ConsoleView` returns `TextConsoleBuilderFactory.getInstance().createBuilder(project).console` (SDK docs `execution.md`).
- Stopping: `handler.destroyProcess()`. `KillableColoredProcessHandler` sends a soft kill (SIGINT, or Ctrl+C on Windows through the platform helper) and kills hard if that's ignored.

### 4.5 `core/Protocol.kt` (the only place that defines message shapes)

The header says: shapes copied from microsoft/playwright `packages/protocol/src/protocol.yml` @ v1.56.0 (identical on main @ 7135071), and the framing comes from playwright-vscode `src/backend.ts`. Microsoft's copyright header goes first, ours below it.

```
const val PROTOCOL_PINNED_PLAYWRIGHT = "1.56.0"
const val DEBUG_CONTROLLER_GUID = "DebugController"
const val DEBUG_CONTROLLER_QUERY = "debug-controller"   // wsEndpoint + "?debug-controller"
const val LISTENING_PREFIX = "Listening on "            // run-server and show-trace stdout

@Serializable enum class RecorderMode { @SerialName("inspecting") INSPECTING, @SerialName("recording") RECORDING, @SerialName("none") NONE }

// Outgoing envelope
@Serializable data class Request(val id: Int, val guid: String = DEBUG_CONTROLLER_GUID, val method: String, val params: JsonObject, val metadata: Metadata = Metadata())
@Serializable data class Metadata(val timeout: Int = 0)

// Command params
@Serializable data class InitializeParams(val codegenId: String = "playwright-test", val sdkLanguage: String = "javascript")
@Serializable data class SetReportStateChangedParams(val enabled: Boolean)
@Serializable data class SetRecorderModeParams(val mode: RecorderMode, val testIdAttributeName: String? = null, val generateAutoExpect: Boolean? = null)
@Serializable data class HighlightParams(val selector: String? = null, val ariaTemplate: String? = null)
// hideHighlight, resume, kill: params {}

// Incoming
@Serializable data class Incoming(val id: Int? = null, val guid: String? = null, val method: String? = null, val params: JsonObject? = null, val result: JsonElement? = null, val error: ErrorPayload? = null)
@Serializable data class ErrorPayload(val error: SerializedError? = null, val value: JsonElement? = null)
@Serializable data class SerializedError(val message: String? = null, val stack: String? = null, val name: String? = null)

// Events
@Serializable data class InspectRequested(val selector: String, val locator: String, val ariaSnapshot: String)
@Serializable data class SetModeRequested(val mode: String)
@Serializable data class StateChanged(val pageCount: Int)
@Serializable data class SourceChanged(val text: String, val header: String? = null, val footer: String? = null, val actions: List<String>? = null)
@Serializable data class Paused(val paused: Boolean)

object Methods { INITIALIZE="initialize"; SET_REPORT_STATE_CHANGED="setReportStateChanged"; SET_RECORDER_MODE="setRecorderMode"; HIGHLIGHT="highlight"; HIDE_HIGHLIGHT="hideHighlight"; RESUME="resume"; KILL="kill" }
object Events  { INSPECT_REQUESTED="inspectRequested"; SET_MODE_REQUESTED="setModeRequested"; STATE_CHANGED="stateChanged"; SOURCE_CHANGED="sourceChanged"; PAUSED="paused" }

val ProtocolJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
```

Step 1 has the full file, but only `DebugControllerClient` (step 4) uses it.

### 4.6 `core/DebugControllerClient.kt`

This is a stub in step 1: the class, the `Listener` interface, and the method signatures, each throwing `NotImplementedError("step 4")`. That lets the UI compile against it. Step 4 implements it (section 7).

### 4.7 `ui/PlaywrightToolWindowFactory.kt`

- `class PlaywrightToolWindowFactory : ToolWindowFactory, DumbAware`
- `override suspend fun isApplicableAsync(project)` returns true only when `PlaywrightProjectLocator.configs` isn't empty (the tool window shows up only in Playwright projects).
- `createToolWindowContent` adds three `Content`s through `ContentFactory.getInstance().createContent(...)`: **Record** (`RecordTab`), **Traces** (`TracesTab`), **Agents** (`AgentsTab`). Each tab is a `SimpleToolWindowPanel` with a toolbar (`ActionManager.createActionToolbar`) and a body. Each `Content` gets a `Disposable` (`content.setDisposer`).
- **Config picker:** a `ComboBoxAction` subclass `ConfigPickerAction` in the tool window title actions (`toolWindow.setTitleActions`). It lists the configs by path relative to the project, writes `PwSettings.selectedConfigPath`, and publishes `CONFIGS_CHANGED`.
- In step 1 the tabs are shells: Record shows "Record new / Record at cursor / Pick locator" buttons that are disabled with "coming in a later step"; Traces shows an empty list; Agents shows an empty console and the backend label.
- plugin.xml: `<toolWindow id="Playwright" anchor="right" icon="/icons/playwright.svg" factoryClass="dev.uzi.pw.ui.PlaywrightToolWindowFactory" secondary="false"/>` (SDK docs `tool_windows.md`). The icon is our own simple SVG (13×13 and 16×16, light and dark), not Microsoft's logo.

### 4.8 `ui/RecordTab.kt`, `ui/TracesTab.kt`, `ui/AgentsTab.kt`

Step 1 shells with the layouts filled in later:
- RecordTab: toolbar (Record new, Record at cursor, Pick locator, Stop, Close browser), a locator `EditorTextField` row with Copy and Insert buttons, and a status label.
- TracesTab: `OnePixelSplitter` with a `Tree` of traces on the left and the viewer panel on the right.
- AgentsTab: action row (Initialize, Plan, Generate, Heal, Stop) and a `ConsoleView` from `NodeRunner.createConsole()`.

### 4.9 `core/PwStartupActivity.kt`

`class PwStartupActivity : ProjectActivity { override suspend fun execute(project) }` waits for smart mode (`DumbService.getInstance(project).waitForSmartMode()` inside the suspend context through `smartReadAction`), then calls `PlaywrightProjectLocator.refresh()`, which notifies on unsupported versions. Registered with `<postStartupActivity implementation="dev.uzi.pw.core.PwStartupActivity"/>`.

### 4.10 `core/PwVfsListener.kt`

`class PwVfsListener(project) : BulkFileListenerBackgroundable`, subscribed in plugin.xml `<projectListeners><listener class=... topic="com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable"/>`. **To check after step 0:** the exact topic: the 2026.1 notable-changes page names `VirtualFileManager.VFS_CHANGES_BG`. If it can't be declared in XML, subscribe in `PwStartupActivity` with `project.messageBus.connect(disposable).subscribe(VirtualFileManager.VFS_CHANGES_BG, ...)`. Step 1 reacts to config files and `package.json` under `node_modules/@playwright/test` (version changes). Step 2 adds `trace.zip`.

### 4.11 `core/PwTopics.kt`, `core/PwNotifications.kt`

Topic definitions (`CONFIGS_CHANGED`, `TRACES_CHANGED`, `RECORDER_STATE`). Notification helper over `NotificationGroupManager.getInstance().getNotificationGroup("Playwright")`, plus `<notificationGroup id="Playwright" displayType="BALLOON" key="notification.group" bundle="messages.PwBundle"/>`.

### 4.12 Actions in step 1

- `actions/InstallBrowsersAction.kt` (Tools menu) runs `cli.js install` through `NodeRunner` and shows the output in a `RunContentManager` tab ("Playwright: install"). This ports the intent of `installer.ts`, which offers install when an error message contains "Looks like Playwright Test or Playwright".
- `actions/PwActionBase.kt` is a shared `AnAction` base with `getActionUpdateThread() = BGT` and `update()` that disables the action when there's no supported config.

### plugin.xml after step 1

`<id>dev.uzi.playwright</id>`, `<name>Playwright for IntelliJ</name>`, vendor, description, `<depends>com.intellij.modules.platform</depends>`, `<depends>JavaScript</depends>`, `<resource-bundle>messages.PwBundle</resource-bundle>`, plus extensions: `toolWindow`, `projectConfigurable`, `postStartupActivity`, `notificationGroup`. Project listener. `<actions>`: group `Playwright.Tools` added to `ToolsMenu` with `Playwright.InstallBrowsers` (and later `Playwright.InitAgents`). The services are `@Service` light services, so they need no `<projectService>` lines. The brief asked for `projectService` entries; light services are the documented replacement and do the same job. **Decision: light services.**

**Compile after step 1:** `./gradlew compileKotlin`, then `./gradlew buildPlugin`.

---

## 5. `--trace on` injection

**Facts:**
- The WebStorm EP list (`generated_webstorm_extension_point_list.md`) has `JavaScript.nodeRunConfigurationExtension` → `AbstractNodeRunConfigurationExtension`. It doesn't list any Playwright-specific EP, and nothing in the SDK docs mentions Playwright. The JS plugin is closed source.
- Playwright has **no environment variable** for trace mode (every `process.env.PW*` read in `packages/playwright/src` was checked). Only the CLI flag `--trace <mode>` or the config's `use.trace` work.

**Plan (the hook is confirmed with `javap` before step 2 code is written):**

1. **Primary: `JavaScript.nodeRunConfigurationExtension`.** `TraceRunConfigurationExtension : AbstractNodeRunConfigurationExtension`. `isApplicableFor(profile)` returns true when the class name of the profile matches the Playwright run configuration (found via `ConfigurationTypeUtil` / `RunConfiguration.type.id`, read from the cached jar). When `PwSettings.alwaysRecordTrace` is on, it adds the trace flag. If the extension can only touch **Node** options and env (likely, since the API is node-run oriented), we add `--require <plugin>/js/pw-trace-preload.cjs` to the node options. That preload, shipped as a plugin resource, appends `--trace` `on` to `process.argv` when `process.argv` contains the `test` command and no `--trace` is present. Workers don't see `test` in argv, so they're unaffected. Commander parses argv after preloads run, so the flag gets through.
2. **Fallback A** (the Playwright config doesn't go through Node run-config extensions): `RunManagerListener` (platform API) on `runConfigurationAdded`/`Changed`. For Playwright configs, `writeExternal(Element)`, append `--trace on` to the "Playwright options" attribute (name read from the serialized XML; the owner can paste a `.run/*.run.xml` to confirm), then `readExternal(Element)`. Turning the setting off removes it. This edits the user's config, so the setting says so.
3. **Fallback B**: no automatic injection. A "Run with trace" editor/gutter action clones the selected Playwright run config (`RunManager.createConfiguration(...).also { copy }`), applies fallback A to the temporary copy only, and runs it with `ProgramRunnerUtil.executeConfiguration`.

Whichever way it's injected, `TraceWatcher` (step 2) finds the resulting `trace.zip` files on its own.

---

## 6. Step 2: trace viewer (file/class level)

Depends on step 1 (locator, NodeRunner, VFS listener, tool window).

- `trace/TraceWatcher.kt`: extends `PwVfsListener` handling. Each `VFileCreateEvent`/`VFileContentChangeEvent` whose path ends in `/trace.zip` and sits under some `PwProjectInfo.outputDir` is added to an in-memory `TraceIndex` (path, mtime, project name, and the test name taken from the parent folder, which Playwright names `<file>-<title>-<project>[-retryN]`). Capped at the 200 most recent. Publishes `TRACES_CHANGED`. On start it does one `VfsUtil.iterateChildrenRecursively` scan per outputDir (background, read action) and calls `LocalFileSystem.addRootToWatch` for each outputDir.
- `trace/TraceService.kt` (project service; ported from `spawnTraceViewer.ts`) keeps one long-lived process: `node cli.js show-trace --host 127.0.0.1 --port 0 --stdin` with env `PWTEST_UNDER_TEST=1` (stops `open()`; see section 2.5), cwd = workingDir. It parses `Listening on (.*)` from stdout and gets the URL. `open(zip)` writes `"<absolute zip path>\n"` to stdin, as vscode does: `StdinServer` loads the new trace into the open viewer. `close()` closes stdin and calls `destroyProcess()`. Restarted if the config changes.
- `trace/TraceViewerPanel.kt`: if `JBCefApp.isSupported()`, a `JBCefBrowser` (disposed with the Content) calls `loadURL(url)`. Otherwise `BrowserUtil.browse(url)` and a label with an "Open in browser" link. A toolbar button "Open in system browser" is always there.
- `trace/TraceRunConfigurationExtension.kt` + `src/main/resources/js/pw-trace-preload.cjs`, per section 5. The preload is a tiny original file, not ported.
- `ui/TracesTab.kt`: tree grouped by test name, newest first; double-click calls `TraceService.open`; "Reveal in Files" and "Delete" in the context menu.
- plugin.xml: `<extensions defaultExtensionNs="JavaScript"><nodeRunConfigurationExtension implementation=.../></extensions>`, used only if javap confirms the primary path.

Compile: `compileKotlin`, `buildPlugin`.

---

## 7. Step 3: record new test with the codegen fallback

Depends on steps 1–2.

- `recorder/RecordNewAction.kt`: tool window button plus `Playwright.RecordNew` action. Picks a config and a project (popup when there are several), then calls `RecorderService.recordNew(cfg, project, url)` (URL from settings, or asked for with `Messages.showInputDialog` when empty).
- `recorder/NewTestFile.kt`: finds the first free `recorded-N.spec.ts` (N = 1..99) under `PwProjectInfo.testDir/tests/`. **Decision:** if `testDir` already ends in `tests`, the file goes straight into testDir instead of `tests/tests/`. It writes the template inside `WriteCommandAction.runWriteCommandAction` (VFS `createChildData` + `VfsUtil.saveText`) and opens it with `FileEditorManager.openFile`.
- `recorder/RecorderService.kt` (step 3 part only): `codegenFallback(cfg, file, url)` runs `node cli.js codegen --target playwright-test -o <file> [--test-id-attribute <attr>] <url>` (flags checked in `playwright-core/src/cli/program.ts` on main and 1.56). It runs in a "Playwright: codegen" console. On `processTerminated` it calls `VfsUtil.markDirtyAndRefresh(true, false, false, file)`, then `FileDocumentManager.reloadFromDisk(doc)` on the EDT. The tool window shows a Stop button (`destroyProcess`).

Compile: `compileKotlin`, `buildPlugin`.

---

## 8. Step 4: DebugControllerClient, live recording, record at cursor, pick locator

### 8.1 Debug-controller wire protocol (quoted from source)

From playwright-vscode `src/reusedBrowser.ts` and `src/backend.ts` @ 27fa358; the server side is `packages/protocol` at Playwright **v1.56.0** (`src/protocol.yml`), the same on main @ 7135071 (`spec/playwright.yml`):

Start (reusedBrowser.ts `_startBackendIfNeeded`):
```
args = [config.cli, 'run-server', `--path=/${createGuid()}`, '--host', '127.0.0.1']
env  += PW_CODEGEN_NO_INSPECTOR=1, PW_EXTENSION_MODE=1
stdout match /Listening on (.*)/  -> wsEndpoint     (driver.ts: console.log('Listening on ' + wsEndpoint))
connect to wsEndpoint + '?debug-controller'          (Backend.rewriteWsEndpoint; server branch: url.searchParams.has('debug-controller'))
```
Closing the server's stdin exits it (`driver.ts`: `process.stdin.on('close', () => gracefullyProcessExitDoNotHang(0))`).

Request framing (backend.ts `send`):
```
{ id, guid: 'DebugController', method, params, metadata: { timeout: 0 } }
```
Response: `{ id, result }` or `{ id, error: { error: { message, stack } } | { value } }`. Events have no `id` and come as `{ method, params }` (backend.ts: `if (!message.id) this.emit(message.method, message.params)`).

Commands (protocol.yml v1.56.0):
```
initialize            { codegenId: string, sdkLanguage: SDKLanguage }      // vscode: { codegenId: 'playwright-test', sdkLanguage: 'javascript' }
setReportStateChanged { enabled: boolean }                                  // vscode: { enabled: true }
setRecorderMode       { mode: 'inspecting'|'recording'|'none', testIdAttributeName?: string, generateAutoExpect?: boolean }
highlight             { selector?: string, ariaTemplate?: string }
hideHighlight         {}
resume                {}
kill                  {}
```
Events:
```
inspectRequested { selector: string, locator: string, ariaSnapshot: string }
setModeRequested { mode: string }
stateChanged     { pageCount: int }
sourceChanged    { text: string, header?: string, footer?: string, actions?: string[] }
paused           { paused: boolean }
```
Server behaviour that matters (debugController.ts): `setRecorderMode` with a mode other than `none` launches Chromium if no browser exists (`headless: !!process.env.PW_DEBUG_CONTROLLER_HEADLESS`) and creates a page if there is none. `sourceChanged.actions` is the **full** list of generated action strings so far, re-sent on every new action or signal. Actions reset when the mode goes to `none`/`standby`.

### 8.2 Files

- `core/DebugControllerClient.kt` (ported from `backend.ts` + the `Backend` class in `reusedBrowser.ts`): starts `run-server` through `NodeRunner` (KillableColoredProcessHandler; stdout listener for `Listening on`, with a 30 s timeout). It connects with the JDK's `java.net.http.HttpClient.newWebSocketBuilder()` (no extra dependency), buffering partial text frames until `last = true`. `AtomicInteger` ids and a `ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>`. `suspend fun send(method, params)`. Typed wrappers: `initialize()`, `setReportStateChanged(true)`, `setRecorderMode(params)`, `highlight(params)`, `hideHighlight()`, `resume()`, `kill()`. A listener interface with one callback per event, delivered on a `Dispatchers.Default` child scope. `dispose()` sends `kill` (fire and forget), closes stdin, then `destroyProcess()` after 2 s. Connection-closed and process-exit events fail every pending call.
- `recorder/RecorderService.kt` (project service; ports `ReusedBrowser`): holds one client, `mode`, `pageCount`, and an edit queue (`Channel<() -> Unit>` consumed on the EDT, ported from `_scheduleEdit`).
  - **Record new (live):** create the file (step 3) with this body, where `<url>` is the record URL:
    ```ts
    import { test, expect } from '@playwright/test';

    test('test', async ({ page }) => {
      await page.goto('<url>');
      // Recording...
    });
    ```
    1. Start the client. 2. Open the browser the way vscode's `_showBrowserForRecording` does: run the new test once with `node cli.js test <file> -c <config> --project <name> --headed --workers 1 --reporter line` and env `PW_TEST_CONNECT_WS_ENDPOINT=<wsEndpoint>` and `PW_TEST_REUSE_CONTEXT=1`. Both env vars are confirmed in `packages/playwright/src/index.ts` (`connectOptionsFromEnv`, `_reuseContext`). The test opens a page in the reused context, runs `goto`, and leaves the page open. 3. `setRecorderMode({mode: recording, testIdAttributeName})`. 4. On each `sourceChanged`, rebuild the test body as `goto` line + `indentBlock(actions.joinToString("\n"), 2)`, replacing the `// Recording...` line and everything recorded so far. A `RangeMarker` covers the recorded region, and each change is applied in `WriteCommandAction.runWriteCommandAction(project, "Playwright: record", groupId, ...)` on that range. The whole recorded region is rewritten every time, which also covers the server collapsing or updating the last action (e.g. `fill`). The goto line has to be kept explicitly because `text` won't contain it (it ran before recording started), so we don't paste `text` wholesale. 5. Stop runs `setRecorderMode({mode: none})`. If the client fails to start or connect, the codegen fallback from step 3 runs instead.
  - **Events:** `setModeRequested(standby)` while recording ends the recording in the UI. `setModeRequested(recording)` without an active recording starts one in the current editor (vscode `_onRecord`). `stateChanged.pageCount == 0` while not recording stops the client (vscode `_pageCountChanged`). `paused` shows a non-modal notification with a "Resume" action that sends `resume`.
- `recorder/InsertionTracker.kt` ports `guessIndentation` and `indentBlock` from `reusedBrowser.ts`, plus a `RangeMarker` wrapper (`document.createRangeMarker(start, end).apply { isGreedyToRight = true }`). `replace(actions)` replaces the marked text with the indented, newline-joined actions and re-creates the marker over the new text.
- `recorder/RecordAtCursorAction.kt` (editor popup and tool window): `update()` runs in BGT. It finds the `PsiElement` at the caret and walks up to a `JSFunction` (arrow or function expression) that is an argument of a `JSCallExpression` whose callee text is `test`, `test.only`, `test.skip`, `test.fixme`, `test.step`, `it`, or `<ident>.extend(...)`-derived names. **Decision:** any callee whose last segment is `test`/`step` or whose qualifier resolves to a `test` variable. The caret has to be inside the function body (`JSBlockStatement`). `actionPerformed` captures the document and caret offset, creates a zero-length `RangeMarker` there, opens the browser (setRecorderMode itself opens `about:blank`; if a default URL is set it uses the bootstrap from the next item), and calls `setRecorderMode(recording)`. Each `sourceChanged` runs `InsertionTracker.replace(actions)` in a `WriteCommandAction`.
- `recorder/BootstrapRunner.kt`: for record-at-cursor with a URL, writes a temporary config and spec under `PathManager.getTempPath()/pw-intellij/<guid>/`. They `require()` the project's `@playwright/test` by absolute path, the config sets `testDir: '.'`, and the spec does `await page.goto(url)`. They run with the same env as above. **Decision:** the bootstrap doesn't inherit the user's config `use` options; this is noted in the UI.
- `locator/LocatorPicker.kt` (project service): `pick()` calls `setRecorderMode({mode: inspecting, testIdAttributeName})`. `inspectRequested` puts `locator` into the Record tab's `EditorTextField` (TypeScript file type, so it gets highlighting). Edits are debounced by 300 ms (`Alarm`) and call `highlight({selector: text})`. The server accepts a locator string there: `unsafeLocatorOrSelectorAsSelector` in debugController.ts. Copy uses `CopyPasteManager`. Insert at caret runs `WriteCommandAction` into the last focused text editor (`FileEditorManager.selectedTextEditor`).
- `locator/HighlightLocatorAction.kt` (editor popup): available when the caret is in a `JSCallExpression` chain starting at `page.` (or any receiver) whose method is `locator|getBy*|frameLocator`. It takes the outermost locator chain text without the receiver (e.g. `getByRole('button', { name: 'Save' })`), calls `highlight({selector})`, and starts the client and browser first if needed.
- `ui/RecordTab.kt`: wires up the buttons and state (`RECORDER_STATE` topic).

Compile: `compileKotlin`, `buildPlugin`.

---

## 9. Step 5: agents, backend A

Depends on steps 1–3 (NodeRunner, locator, consoles).

### 9.1 Files

- `agents/AgentBackend.kt`: `interface AgentBackend { val id; fun isAvailable(cfg): Availability; fun agentFilesPresent(root): Boolean; fun initLoop(): String; fun commandLine(kind: AgentKind, prompt: String, cfg: PwConfig): GeneralCommandLine; fun parseLine(line: String): AgentOutput? }`. `enum AgentKind { PLANNER, GENERATOR, HEALER }`.
- `agents/ClaudeCliBackend.kt`, `agents/CodexCliBackend.kt`, `agents/OpencodeCliBackend.kt` (three small files; the brief lists only Claude, but the same interface covers all three).
- `agents/AgentService.kt` (project service): one active run at a time. `run(kind, prompt)` builds the command line, starts it with `KillableColoredProcessHandler`, streams lines through `ProcessAdapter.onTextAvailable` into `parseLine`, and prints readable text to the Agents tab console. It also watches the VFS (via `PwVfsListener`) for new `specs/*.md` and `*.spec.ts` files created during the run and opens them. `stop()` calls `destroyProcess()`.
- `agents/InitAgentsAction.kt` (Tools menu, plus a notification action): runs `node cli.js init-agents --loop=<claude|codex|opencode> -c <config>` in workingDir, which is where the files are written (the generators use relative paths, so cwd is the repo root). It saves `lastInitAgentsVersion` and `lastInitAgentsLoop`. `PwStartupActivity` compares the saved version with the current Playwright version and the presence of the agent files: `.claude/agents/playwright-test-*.md` (1.56+ for claude), `.codex/agents/playwright_test_*.toml` (codex, 1.61+), `.opencode/prompts/playwright-test-*.md` + `opencode.json` (opencode). If anything is missing or the version changed, it shows a notification with "Initialize agents".
- `agents/PlanAction.kt`: dialog with seed URL and description; prompt `@agent-playwright-test-planner <description>. Seed URL: <url>`. Opens new files under `specs/` as they appear and when the run ends.
- `agents/GenerateTestsAction.kt` (`ProjectViewPopupMenu` and `EditorPopupMenu`, `.md` under `specs/`): prompt `@agent-playwright-test-generator Generate tests for <relative path>[ section "<heading>"]`. The section is the Markdown heading that contains the caret or selection, found by line scan. Opens new `*.spec.ts` files.
- `agents/HealTestAction.kt`: (a) in `TestTreePopupMenu` (`IdeActions.GROUP_TESTTREE_POPUP`, confirmed in intellij-community `IdeActions.java` @ 261.27258.48), enabled when `AbstractTestProxy.DATA_KEY` is an `SMTestProxy` with `isDefect`. The test location comes from `proxy.getLocation(project, GlobalSearchScope.projectScope(project))` → `PsiLocation` → file and enclosing `test(...)` title. No `locationUrl` format is assumed. (b) Editor popup "Heal this test" on a `test(...)` call. (c) Gutter: `HealLineMarkerProvider` (`codeInsight.lineMarkerProvider`, language `JavaScript`, and `TypeScript` if the JS plugin registers it separately) shows an icon on a `test(...)` call whose `file:title` is in `FailedTestsRegistry`. That registry is fed by `SMTRunnerEventsListener.TEST_STATUS` (`onTestFailed`, `onTestFinished`), a platform listener. Before the run: `LocalHistory.getInstance().putSystemLabel(project, "Before Playwright heal: <title>")` (API confirmed in `LocalHistory.kt` @ 261), plus an in-memory snapshot of the test file and all files under testDir that are open or that the run touches (via the VFS listener). After the run: for each changed file, a `SimpleDiffRequest` (`DiffContentFactory.create(project, before)` vs the current document) shown with `DiffManager.getInstance().showDiff`, with a chain when several files changed. Prompt: `@agent-playwright-test-healer Fix the failing test "<title>" in <relative file>`.
- `ui/AgentsTab.kt`: backend label with availability reason, action buttons, Stop, and the console.

### 9.2 Exact CLI invocations

All three: **working directory** = `PwConfig.workingDir` (where `init-agents` wrote its files); **parent env** = console env; **binary** = `PwSettings.agentCliPath`, or found on `PATH` with `PathEnvironmentVariableUtil.findInPath(name)` (`claude`, `codex`, `opencode`; on Windows `.cmd`/`.exe` are resolved through `PathEnvironmentVariableUtil.findExecutableInWindowsPath`). The plugin never goes through a shell. The prompt is passed as a single argv element.

**MCP server:** the `.mcp.json`, `.codex/agents/*.toml` and `opencode.json` that `init-agents` writes start the server with `npx playwright run-test-mcp-server`. That's the user's file and we leave it alone. Claude is the one CLI we can override per run without editing files, so for Claude we pass our own server definition that uses node directly.

**Claude Code** (flags from code.claude.com `cli-reference`):
```
claude -p "<prompt>"
       --output-format stream-json --verbose
       --permission-mode acceptEdits
       --mcp-config '{"mcpServers":{"playwright-test":{"command":"<node>","args":["<cli.js>","run-test-mcp-server","-c","<config>"]}}}'
       --strict-mcp-config
       --allowedTools "mcp__playwright-test" "Read" "Glob" "Grep" "LS" "Edit" "MultiEdit" "Write"
```
- The server name `playwright-test` matches the tool names the generated agent files reference (`mcp__playwright-test__*`, from `ClaudeGenerator.agentSpec`).
- `--mcp-config` with `-p` waits for pending servers before the first turn (cli-reference).
- Streaming: `stream-json` writes one JSON object per line on stdout. `parseLine` prints `assistant` text blocks, `tool_use` names, and the final `result`. Lines that don't parse are printed raw.
- Stop: `destroyProcess()` (SIGINT, then kill).

**Codex** (Playwright ≥1.61; flags from developers.openai.com `noninteractive.md` and `subagents.md`):
```
codex exec --json --sandbox workspace-write "<prompt>"
```
- Codex has no `@agent` mention or `--agent` flag. Its subagents are spawned by asking. Prompt template: `Spawn the playwright_test_<kind> agent to do the following: <task>` (snake_case names come from `CodexGenerator.codexName`).
- Streaming: `--json` turns stdout into JSONL (`thread.started`, `turn.*`, `item.*`, `error`). Progress also goes to stderr, and both are printed.
- Codex needs a git repo. If `workingDir` isn't inside one, the run is refused with a message. We don't add `--skip-git-repo-check` on our own; a settings checkbox can turn it on.
- Stop: `destroyProcess()`.

**OpenCode** (flags from opencode.ai `docs/cli.md`, `docs/agents.md`):
```
opencode run --agent build --format json --dir <workingDir> "@playwright-test-<kind> <task>"
```
- `@`-mention is the documented way to invoke a subagent by hand. The subagents are defined in the generated `opencode.json` (`mode: "subagent"`). `--format json` prints raw JSON events.
- Stop: `destroyProcess()`.

### 9.3 Availability rules

- Claude: binary found, `.claude/agents/playwright-test-{planner,generator,healer}.md` all exist.
- Codex: Playwright ≥1.61, binary found, `.codex/agents/playwright_test_*.toml` exist.
- OpenCode: binary found, `opencode.json` + `.opencode/prompts/playwright-test-*.md` exist.

Compile: `compileKotlin`, `buildPlugin`.

---

## 10. Step 6: backend B (design only, no code)

- `agents/McpDirectBackend.kt` (future). Dependency `io.modelcontextprotocol:kotlin-sdk-client:0.16.0`, excluding `org.jetbrains.kotlinx:kotlinx-coroutines-*` and `kotlinx-serialization-*` (the platform provides them; the SDK docs warn against bundling coroutines). Ktor client engine `ktor-client-cio`, version from the SDK's own BOM, bundled. Check for package clashes against the platform's Ktor before adopting it.
- Transport: stdio. Spawn `node <cli.js> run-test-mcp-server -c <config> [--headless]` with `NodeRunner` (option list checked in `program.ts` 1.56 and main) and connect the SDK `Client` over `StdioClientTransport(process.inputStream, process.outputStream)`.
- Loop: read the agent's instructions from the generated agent `.md` (Claude format; YAML front-matter `tools:` list → allowed MCP tools). The system prompt is the agent instructions. Call the LLM API with the tool schemas from `client.listTools()` and route `tool_use` calls to `client.callTool`. The API key comes from PasswordSafe. Plan only: the planner agent's tools are search plus `playwright-test/*` browser tools, and its output (the Markdown plan) is written by our code into `specs/`.
- No code before the owner approves this design.

---

## 11. Compile checks after each step

| Step | Command (run in the sandbox: bash; on the owner's machine: PowerShell `.\gradlew.bat ...`) |
|---|---|
| 0 | `./gradlew compileKotlin` (downloads IDEA 2026.1.5), then read-only `javap` checks |
| 1 | `./gradlew compileKotlin`, `./gradlew buildPlugin` |
| 2 | same |
| 3 | same |
| 4 | same |
| 5 | same |
| 6 | none (design only) |

Nothing else is run: no `test`, `check`, `runIde`, `verifyPlugin` or `build`.

Owner, local (PowerShell):
```powershell
git config core.hooksPath .githooks
```
```powershell
.\gradlew.bat buildPlugin
```
The zip lands in `build\distributions\`. Install it with Settings → Plugins → ⚙ → Install Plugin from Disk.

---

## 12. Open questions

Only one blocks anything, and only the NOTICE/vendor text:

1. **Copyright holder and vendor name** for `NOTICE`, `LICENSE` header lines, and `plugin.xml <vendor>` (name, plus email/URL if wanted). Until you answer, I'll use `Uzi Cohen` with `https://github.com/uzi-cohen`.

Non-blocking (decided, flagged so you can override):
- Plugin display name. Plugin Verifier has "forbidden word in plugin name" checks (SDK docs FAQ "How to mute specific problems in pluginVerification"), and Marketplace may reject "IntelliJ" in the name. Since we never publish, I'll keep **"Playwright for IntelliJ"**; rename before any Marketplace upload.
- If javap shows the Playwright run configuration ignores `nodeRunConfigurationExtension`, "Always record trace" uses fallback A (edits run configurations, and the setting says so).

---

## 13. Implementation notes (steps 0–5 built)

- **Repositories:** IntelliJ repositories first, then JetBrains' Maven Central mirror (`https://cache-redirector.jetbrains.com/maven-central`) above `mavenCentral()`. In `pluginManagement`, `gradlePluginPortal()` sits above `mavenCentral()`, because `foojay-resolver` and `intellij-platform-gradle-plugin` exist only on the portal, and a 429 from Maven Central fails resolution instead of falling through.
- **`--trace on` hook (section 5), as built:**
  - **What javap showed:** the Playwright run configuration is `com.intellij.aqua.runners.playwright.js.PlaywrightRunConfiguration`, type id `JavaScriptTestRunnerPlaywright`. It lives in an internal content module of the JavaScript plugin. `PlaywrightRunProfileState` never calls `NodeRunConfigurationExtensionsManager`, so neither `JavaScript.nodeRunConfigurationExtension` nor a `--require` preload would run. The settings expose `getCliOptions()`/`builder().setCliOptions()`, and those CLI options are appended after `playwright test`.
  - **What was built:** `trace/TraceProgramRunner` is a `ProgramRunner` registered `order="first"`. While "Always record trace" is on, it clones the Playwright config, adds `--trace on` to the clone's CLI options (through reflection, because the classes are internal), and hands the clone to the runner that would have run it. The user's config is never modified.
- **Record new with a URL:** the new file contains `await page.goto(url)`. It is run once against the debug controller's browser (`PW_TEST_CONNECT_WS_ENDPOINT` + `PW_TEST_REUSE_CONTEXT=1`), then recording replaces the `// Recording...` line.
- **Record at cursor with a default URL:** a throwaway config and spec in the IDE temp dir open the URL. The user's config `use` options are not applied there.
- **Agents:**
  - `VirtualFileManager.VFS_CHANGES` (not the BG topic) is used during runs, because heal needs `before()` events to capture old contents.
  - The OpenCode `--format json` event shapes are undocumented, so lines that don't parse are printed raw.
