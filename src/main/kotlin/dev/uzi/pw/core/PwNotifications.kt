/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project

/** Balloon notifications in the "Playwright" notification group. */
object PwNotifications {
    private const val GROUP_ID = "Playwright"

    fun info(project: Project?, content: String, vararg actions: AnAction) = show(project, content, NotificationType.INFORMATION, actions)

    fun warn(project: Project?, content: String, vararg actions: AnAction) = show(project, content, NotificationType.WARNING, actions)

    fun error(project: Project?, content: String, vararg actions: AnAction) = show(project, content, NotificationType.ERROR, actions)

    private fun show(project: Project?, content: String, type: NotificationType, actions: Array<out AnAction>) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }
}
