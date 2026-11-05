/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Scans for Playwright configs once the project is open; the locator warns about unsupported versions. */
class PwStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        PlaywrightProjectLocator.getInstance(project).refresh()
        // Listens for test selection / finished tests to open their traces ("Show trace viewer").
        dev.uzi.pw.trace.ShowTraceOnSelection.getInstance(project)
    }
}
