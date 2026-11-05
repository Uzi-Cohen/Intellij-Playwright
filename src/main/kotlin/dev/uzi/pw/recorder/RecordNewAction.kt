/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.recorder

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.uzi.pw.PwBundle
import dev.uzi.pw.actions.PwActionBase
import dev.uzi.pw.core.PwConfig
import dev.uzi.pw.core.PwProjectInfo
import dev.uzi.pw.settings.PwSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Record a new test into `tests/recorded-N.spec.ts`. */
class RecordNewAction : PwActionBase() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = config(e) ?: return
        start(project, config)
    }

    companion object {
        fun start(project: Project, config: PwConfig) {
            val url = askUrl(project) ?: return
            RecorderService.getInstance(project).scope.launch {
                val projects = RecorderService.projectsOrNotify(project, config) ?: return@launch
                withContext(Dispatchers.EDT) {
                    chooseProject(project, projects) { target ->
                        RecorderService.getInstance(project).recordNew(config, target, url.ifBlank { null })
                    }
                }
            }
        }

        /** The default record URL, or asks for one. Returns null when cancelled; "" means no URL. */
        fun askUrl(project: Project): String? {
            val default = PwSettings.getInstance(project).state.defaultRecordUrl
            if (!default.isNullOrBlank()) return default
            return Messages.showInputDialog(
                project,
                PwBundle.message("recorder.dialog.url.message"),
                PwBundle.message("recorder.dialog.url.title"),
                null,
                "https://",
                null,
            )?.trim()?.let { if (it == "https://") "" else it }
        }

        /** Calls [onChosen] with the only project, or with the one picked from a popup. */
        private fun chooseProject(project: Project, projects: List<PwProjectInfo>, onChosen: (PwProjectInfo) -> Unit) {
            if (projects.size == 1) {
                onChosen(projects.first())
                return
            }
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(projects)
                .setTitle(PwBundle.message("recorder.dialog.project.message"))
                .setRenderer(SimpleListCellRenderer.create("") { it.name.ifEmpty { it.testDir.toString() } })
                .setItemChosenCallback { onChosen(it) }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }
}
