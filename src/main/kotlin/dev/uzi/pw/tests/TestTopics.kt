/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.tests

import com.intellij.util.messages.Topic

/** The listed tests ([TestDiscovery.state]) changed: a new model, listing started or finished, or a listing error. */
fun interface PwTestModelListener {
    fun testModelChanged()
}

/** The status of one or more tests ([TestResults]) changed. */
fun interface PwTestResultsListener {
    fun testResultsChanged()
}

/** Project-level topics of the test explorer. */
object TestTopics {
    @JvmField
    @Topic.ProjectLevel
    val MODEL_CHANGED: Topic<PwTestModelListener> = Topic(PwTestModelListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmField
    @Topic.ProjectLevel
    val RESULTS_CHANGED: Topic<PwTestResultsListener> = Topic(PwTestResultsListener::class.java, Topic.BroadcastDirection.NONE)
}
