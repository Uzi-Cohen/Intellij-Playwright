/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modifications Copyright 2026 Uzi Cohen
 * Ported to Kotlin for Playwright Toolkit from microsoft/playwright-vscode src/testModel.ts (runTests options when
 * showBrowser is on) and src/reusedBrowser.ts (onWillRunTests / onDidRunTests).
 */
package dev.uzi.pw.run

import com.intellij.openapi.project.Project
import dev.uzi.pw.core.PwException
import dev.uzi.pw.recorder.RecorderService
import dev.uzi.pw.settings.PwSettings

/**
 * "Reuse browser": runs tests in the plugin's debug-controller browser and keeps it open between runs.
 *
 * playwright-vscode (testModel.ts `runTests`), when its show-browser setting is on, passes `headed: true`,
 * `workers: 1`, `trace: 'off'`, `video: 'off'`, `reuseContext: true` and `connectWsEndpoint`. As CLI and env:
 * `--headed` (from [HeadedRunPatch]), `--workers 1`, `--trace off`, `PW_TEST_REUSE_CONTEXT=1` and
 * `PW_TEST_CONNECT_WS_ENDPOINT=<run-server wsEndpoint>` (both read in playwright packages/playwright/src/index.ts).
 * There is no CLI flag for video; Playwright only reuses the context when video is off (index.ts `_reuseContext`).
 */
object ReuseBrowserRunPatch : RunPatchContributor {

    override fun isActive(project: Project): Boolean = PwSettings.getInstance(project).isReuseBrowserEffective

    override suspend fun patch(context: RunPatchContext): RunPatch? {
        val config = context.pwConfig?.takeIf { it.isSupported }
            ?: throw PwException("Reuse browser needs a supported Playwright config")
        val wsEndpoint = RecorderService.getInstance(context.project).prepareForTestRun(config)
        return RunPatch(
            cliArgs = listOf("--workers", "1", "--trace", "off"),
            env = mapOf(
                "PW_TEST_CONNECT_WS_ENDPOINT" to wsEndpoint,
                "PW_TEST_REUSE_CONTEXT" to "1",
            ),
        )
    }

    override fun onRunStarted(context: RunPatchContext) {
        RecorderService.getInstance(context.project).onTestRunStarted()
    }

    override fun onRunFinished(context: RunPatchContext) {
        RecorderService.getInstance(context.project).onTestRunFinished()
    }
}
