/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.locator

import com.intellij.openapi.actionSystem.AnActionEvent
import dev.uzi.pw.actions.PwActionBase

/** Pick a locator in the browser. */
class PickLocatorAction : PwActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = config(e) ?: return
        LocatorPicker.getInstance(project).pick(config)
    }
}
