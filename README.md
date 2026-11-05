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

## Requirements

- IntelliJ IDEA Ultimate or WebStorm 2026.1 or newer (the bundled JavaScript plugin is required)
- `@playwright/test` 1.56 or newer installed in the project's `node_modules`
- A local Node.js interpreter (configured in the IDE, or overridden in Settings | Tools | Playwright)
- For agents: `claude`, `codex` (Playwright 1.61+) or `opencode` on `PATH`

## Build (Windows, PowerShell)

Enable the commit hook once:

```powershell
git config core.hooksPath .githooks
```

Build the plugin zip:

```powershell
.\gradlew.bat buildPlugin
```

The zip is written to `build\distributions\`. Install it with
Settings | Plugins | gear icon | Install Plugin from Disk.

## License

Apache License 2.0. See `LICENSE` and `NOTICE`.
