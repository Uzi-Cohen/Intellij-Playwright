# Playwright Toolkit

Open source plugin for Playwright in IntelliJ IDEA Ultimate and WebStorm, based on Microsoft's
[Playwright Test for VS Code](https://github.com/microsoft/playwright-vscode) extension.

<!-- Plugin description -->
Brings the Playwright Test for VS Code features to JetBrains IDEs, on top of the built-in
Playwright run configurations of the JavaScript plugin:

- Record a new test, or record actions at the caret, with live updates in the editor
- Pick a locator in the browser, edit it, and highlight it back in the page
- Always record traces and view them in an embedded trace viewer
- Playwright test agents (planner, generator, healer) through Claude Code, Codex or OpenCode

Requires Playwright Test 1.56 or newer in a TypeScript/JavaScript project.
<!-- Plugin description end -->

## Install

**Requirements:** IntelliJ IDEA Ultimate or WebStorm **2026.1 or newer** (build 261+). The plugin
depends on the bundled JavaScript plugin. Your project needs Playwright Test 1.56 or newer.

> [!WARNING]
> **Do not install the ZIP from GitHub's green `Code` > `Download ZIP` button.** That file
> (`Intellij-Playwright-main.zip`) is the **source code**, not the plugin. The IDE rejects it with
> *"Fail to load plugin descriptor from file Intellij-Playwright-main.zip"*.
> Install the built plugin, `playwright-intellij-<version>.zip`, from one of the options below.

### Option 1: download the build from GitHub Actions

1. Open the [Build workflow runs on `main`](https://github.com/Uzi-Cohen/Intellij-Playwright/actions/workflows/build.yml?query=branch%3Amain+is%3Asuccess)
   and click the most recent successful run (green check). You must be signed in to GitHub to download artifacts.
2. Under **Artifacts**, download **`playwright-intellij-<version>.zip`**.
   Runs from before this change name the artifact **`plugin-distribution`** instead. That download is a wrapper:
   extract it once and use the `playwright-intellij-<version>.zip` inside. Do not extract the plugin zip itself.
3. In the IDE, go to **Settings | Plugins**, click the gear icon, and choose **Install Plugin from Disk...**.
   Select `playwright-intellij-<version>.zip`.
4. Restart the IDE when it asks you to.

### Option 2: build it yourself (Windows, PowerShell)

Requirements: **JDK 21** (`java -version` should report 21) and Git.

```powershell
git clone https://github.com/Uzi-Cohen/Intellij-Playwright.git
cd Intellij-Playwright
.\gradlew.bat buildPlugin
```

The plugin is written to `build\distributions\playwright-intellij-<version>.zip`. Install that file as in
step 3 of option 1. Do not extract it first.

A valid plugin zip contains `playwright-intellij/lib/playwright-intellij-<version>.jar`. If the zip you
selected contains `src/`, `build.gradle.kts` or another zip, it is not the plugin.

### Using the plugin

- A local Node.js interpreter (configured in the IDE, or overridden in Settings | Tools | Playwright)
- For agents: `claude`, `codex` (Playwright 1.61+) or `opencode` on `PATH`

## Development

Enable the commit hook once:

```powershell
git config core.hooksPath .githooks
```

## License

Apache License 2.0. See `LICENSE` and `NOTICE`.
