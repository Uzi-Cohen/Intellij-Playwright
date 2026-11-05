/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.trace

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwException
import dev.uzi.pw.core.PwTestSelectionListener
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * "Show trace viewer": when a test is selected in the test tree or finishes running, open its trace in the
 * "Playwright Trace" tool window, like playwright-vscode's `_treeItemSelected` / `_showTraceOnTestProgress`
 * (src/extension.ts), which open the test's trace in the trace viewer.
 */
@Service(Service.Level.PROJECT)
class ShowTraceOnSelection(private val project: Project, private val cs: CoroutineScope) {

    init {
        project.messageBus.connect(cs).subscribe(PwTopics.TEST_SELECTED, PwTestSelectionListener { file, _, titles, projectName ->
            if (PwSettings.getInstance(project).state.alwaysRecordTrace) open(Path.of(file), titles, projectName)
        })
    }

    private fun open(file: Path, titles: List<String>, projectName: String?) {
        cs.launch {
            val config = PlaywrightProjectLocator.getInstance(project).selectedConfig()?.takeIf { it.isSupported } ?: return@launch
            val projects = try {
                PlaywrightProjectLocator.getInstance(project).projects(config)
            } catch (e: PwException) {
                return@launch
            }
            val trace = TraceMatcher.find(TraceIndex.getInstance(project).entries(), projects, file, titles, projectName) ?: return@launch
            if (TraceService.getInstance(project).currentFile() == trace.path) return@launch
            TraceService.getInstance(project).open(trace.path)
            withContext(Dispatchers.EDT) {
                ToolWindowManager.getInstance(project).getToolWindow(TRACE_TOOL_WINDOW_ID)?.show()
            }
        }
    }

    companion object {
        const val TRACE_TOOL_WINDOW_ID = "Playwright Trace"

        fun getInstance(project: Project): ShowTraceOnSelection = project.service()
    }
}
