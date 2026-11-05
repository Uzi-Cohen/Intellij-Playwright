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

Check the zip with Plugin Verifier (IntelliJ IDEA 2026.1.5, 2026.2.4, and WebStorm 2026.2.4):

```powershell
.\gradlew.bat verifyPlugin
```

The zip is written to `build\distributions\`. Install it with
Settings | Plugins | gear icon | Install Plugin from Disk.

## Publishing

The first upload to JetBrains Marketplace is manual. After that, the Release workflow
signs and publishes when a GitHub release is published or a version tag is pushed.

Before the first upload, generate a signing key (see Plugin Signing in the IntelliJ Platform
Plugin SDK) and run `.\gradlew.bat signPlugin`. Upload the `*-signed.zip`. Create a
permanent token at https://plugins.jetbrains.com/author/me/tokens and add these repository
secrets under Settings | Secrets and variables | Actions:

| Secret | Value |
| --- | --- |
| `PUBLISH_TOKEN` | Marketplace permanent token |
| `CERTIFICATE_CHAIN` | Contents of `chain.crt` (PEM or its Base64) |
| `PRIVATE_KEY` | Contents of `private.pem` (PEM or its Base64) |
| `PRIVATE_KEY_PASSWORD` | Password for the private key |

`.\gradlew.bat publishPlugin` signs and uploads. It works only after the plugin exists on
Marketplace. Set Actions workflow permissions to read and write so the workflow can open
the changelog pull request.

## License

Apache License 2.0. See `LICENSE` and `NOTICE`.
