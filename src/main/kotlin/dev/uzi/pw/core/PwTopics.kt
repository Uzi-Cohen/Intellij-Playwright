/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.util.messages.Topic

fun interface PwConfigsListener {
    fun configsChanged()
}

fun interface PwTracesListener {
    fun tracesChanged()
}

fun interface PwRecorderStateListener {
    fun recorderStateChanged()
}

/** A test was selected in the test tree, or finished running. [file] is an absolute path, [line] 1-based. */
fun interface PwTestSelectionListener {
    fun testSelected(file: String, line: Int, titlePath: List<String>, projectName: String?)
}

fun interface PwReportsListener {
    fun reportsChanged()
}

fun interface PwSettingsListener {
    fun settingsChanged()
}

/** Project-level message bus topics. */
object PwTopics {
    @JvmField
    @Topic.ProjectLevel
    val CONFIGS_CHANGED: Topic<PwConfigsListener> = Topic(PwConfigsListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmField
    @Topic.ProjectLevel
    val TRACES_CHANGED: Topic<PwTracesListener> = Topic(PwTracesListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmField
    @Topic.ProjectLevel
    val SETTINGS_CHANGED: Topic<PwSettingsListener> = Topic(PwSettingsListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmField
    @Topic.ProjectLevel
    val REPORTS_CHANGED: Topic<PwReportsListener> = Topic(PwReportsListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmField
    @Topic.ProjectLevel
    val TEST_SELECTED: Topic<PwTestSelectionListener> = Topic(PwTestSelectionListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmField
    @Topic.ProjectLevel
    val RECORDER_STATE: Topic<PwRecorderStateListener> = Topic(PwRecorderStateListener::class.java, Topic.BroadcastDirection.NONE)
}
