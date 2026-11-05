---
description: Port a playwright-vscode source file into a Kotlin file of this plugin
argument-hint: <playwright-vscode file> <target Kotlin file>
---

Port `$1` from microsoft/playwright-vscode into `$2` in this plugin.

1. Read `$1` from the playwright-vscode checkout (`../playwright-vscode/src/`). Read every helper it imports
   that the port needs. Do not rely on memory for APIs or message shapes.
2. Write the complete Kotlin file `$2`. Never a partial snippet.
3. The file starts with Microsoft's Apache 2.0 copyright header exactly as in the source, followed by:
   ```
   /*
    * Modifications Copyright 2026 Uzi Cohen
    * Ported to Kotlin for Playwright for IntelliJ from microsoft/playwright-vscode <path>.
    */
   ```
4. Any debug controller message shape goes into `core/Protocol.kt` only.
5. Add the file and its source to the list at the end of `NOTICE`.
6. Run `./gradlew compileKotlin` only. Do not run tests, runIde, verifyPlugin, check, or build. Do not write tests.
7. Commit locally with a semantic message. Do not push. No AI attribution lines.
