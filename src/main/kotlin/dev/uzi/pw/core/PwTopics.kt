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
    val RECORDER_STATE: Topic<PwRecorderStateListener> = Topic(PwRecorderStateListener::class.java, Topic.BroadcastDirection.NONE)
}
