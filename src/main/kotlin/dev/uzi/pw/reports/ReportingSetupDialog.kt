/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import dev.uzi.pw.PwBundle
import javax.swing.JComponent

/** Asks which reporters to set up. Reporters already in the config are shown checked and disabled. */
class ReportingSetupDialog(
    project: Project,
    private val configName: String,
    private val alreadyConfigured: Set<ReporterKind>,
) : DialogWrapper(project) {

    private val boxes = linkedMapOf(
        ReporterKind.HTML to JBCheckBox(PwBundle.message("reports.setup.html")),
        ReporterKind.ALLURE to JBCheckBox(PwBundle.message("reports.setup.allure")),
        ReporterKind.JUNIT to JBCheckBox(PwBundle.message("reports.setup.junit")),
    )
    private val gitignoreBox = JBCheckBox(PwBundle.message("reports.setup.gitignore"), true)

    init {
        title = PwBundle.message("reports.setup.title")
        setOKButtonText(PwBundle.message("reports.setup.ok"))
        for ((kind, box) in boxes) {
            if (kind in alreadyConfigured) {
                box.isSelected = true
                box.isEnabled = false
                box.text = box.text + " " + PwBundle.message("reports.setup.already")
            } else {
                box.isSelected = kind == ReporterKind.HTML || kind == ReporterKind.ALLURE
            }
        }
        init()
    }

    /** Reporters to add (excluding the ones already configured). */
    val selectedKinds: List<ReporterKind>
        get() = boxes.filter { (kind, box) -> box.isSelected && kind !in alreadyConfigured }.keys.toList()

    val updateGitignore: Boolean get() = gitignoreBox.isSelected

    override fun createCenterPanel(): JComponent = panel {
        row { label(PwBundle.message("reports.setup.config", configName)) }
        group(PwBundle.message("reports.setup.reporters")) {
            for ((kind, box) in boxes) {
                row { cell(box) }.also {
                    if (kind == ReporterKind.ALLURE) row { comment(PwBundle.message("reports.setup.allure.comment")) }
                }
            }
        }
        row { cell(gitignoreBox) }
        row { comment(PwBundle.message("reports.setup.preview.comment")) }
    }
}
