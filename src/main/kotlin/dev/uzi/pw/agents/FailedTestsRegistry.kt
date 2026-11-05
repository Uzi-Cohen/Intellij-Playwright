/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.agents

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which tests failed in the last runs (file path + title), fed by [SMTRunnerEventsListener.TEST_STATUS]
 * through [Listener]. A test is forgotten when it passes. Used by the heal gutter icon.
 */
@Service(Service.Level.PROJECT)
class FailedTestsRegistry(private val project: Project, scope: CoroutineScope) {

    data class FailedTest(val path: String, val title: String)

    private class Event(val proxy: SMTestProxy, val failed: Boolean)

    private val failed: MutableSet<FailedTest> = ConcurrentHashMap.newKeySet()
    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        // One consumer keeps the events in order (a failure followed by a pass of the same test).
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.debug("Could not resolve test location", e)
                }
            }
        }
    }

    fun isFailed(path: String, title: String): Boolean = failed.contains(FailedTest(path, title))

    fun all(): Set<FailedTest> = HashSet(failed)

    fun forget(path: String, title: String) {
        if (failed.remove(FailedTest(path, title))) restartHighlighting()
    }

    /** Called from the test events listener with the test's state at that moment. */
    fun record(proxy: SMTestProxy, isFailed: Boolean) {
        if (proxy.isSuite || !proxy.isLeaf) return
        events.trySend(Event(proxy, isFailed))
    }

    private suspend fun handle(event: Event) {
        val test = smartReadAction(project) {
            val element = event.proxy.getLocation(project, GlobalSearchScope.projectScope(project))?.psiElement
                ?: return@smartReadAction null
            val path = element.containingFile?.virtualFile?.path ?: return@smartReadAction null
            val title = TestPsi.enclosingTestCall(element)?.let { TestPsi.testTitle(it) } ?: event.proxy.name
            FailedTest(path, title)
        } ?: return
        val changed = if (event.failed) failed.add(test) else failed.remove(test)
        if (changed) restartHighlighting()
    }

    private fun restartHighlighting() {
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart(this)
    }

    /** Registered in plugin.xml under `projectListeners` for `SMTRunnerEventsListener`. */
    class Listener(private val project: Project) : SMTRunnerEventsAdapter() {
        override fun onTestFailed(test: SMTestProxy) {
            getInstance(project).record(test, true)
        }

        override fun onTestFinished(test: SMTestProxy) {
            when {
                test.isDefect -> getInstance(project).record(test, true)
                test.isPassed -> getInstance(project).record(test, false)
            }
        }
    }

    companion object {
        private val LOG = logger<FailedTestsRegistry>()

        fun getInstance(project: Project): FailedTestsRegistry = project.getService(FailedTestsRegistry::class.java)
    }
}
