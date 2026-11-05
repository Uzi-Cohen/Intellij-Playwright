/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** A project-lifetime coroutine scope for extensions that are not services themselves (e.g. program runners). */
@Service(Service.Level.PROJECT)
class PwProjectScope(val cs: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): PwProjectScope = project.service()
    }
}
