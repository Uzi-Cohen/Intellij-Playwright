/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.tests

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import dev.uzi.pw.agents.TestPsi
import dev.uzi.pw.core.PwTopics
import dev.uzi.pw.run.PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Status of every test in the test tree, fed by [SMTRunnerEventsListener.TEST_STATUS] for runs of the bundled
 * Playwright run configuration (however they were started: the tree, the gutter, the Run tool window).
 *
 * A test proxy is mapped to a [CaseNode] through its PSI location: the enclosing test call
 * ([TestPsi.enclosingTestCall]) gives the file and the line range, and the case of that file whose listed line
 * falls in the range is the one. The project comes from the proxy's ancestor nodes when one is named like a project
 * of the case. Statuses are kept per case and project and survive re-listing (case keys are file + title path).
 */
@Service(Service.Level.PROJECT)
class TestResults(private val project: Project, cs: CoroutineScope) {

    private sealed interface Event
    private class TestEvent(val proxy: SMTestProxy, val root: SMTestProxy.SMRootTestProxy, val status: TestStatus) : Event
    private class RunFinished(val root: SMTestProxy.SMRootTestProxy) : Event

    /** Per run: the (case, project) pairs set to RUNNING and not finished yet, and the cases that finished. */
    private class RunInfo {
        val running = LinkedHashSet<Pair<String, String>>()
        val finished = LinkedHashMap<String, CaseNode>()
        val finishedProject = HashMap<String, String>()
    }

    private val statuses = ConcurrentHashMap<String, ConcurrentHashMap<String, TestStatus>>()
    private val lastProject = ConcurrentHashMap<String, String>()
    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Only touched by the single consumer coroutine. */
    private val runs = IdentityHashMap<SMTestProxy.SMRootTestProxy, RunInfo>()

    init {
        // One consumer keeps the events in order (started, failed, finished).
        cs.launch {
            for (event in events) {
                try {
                    when (event) {
                        is TestEvent -> handle(event)
                        is RunFinished -> finishRun(event.root)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.debug("Cannot map a test event to the test tree", e)
                }
            }
        }
        project.messageBus.connect(cs).subscribe(SMTRunnerEventsListener.TEST_STATUS, Listener())
    }

    /** Status of [node]: per project for a case, propagated from the children for a describe or a file. */
    fun status(node: TestNode): TestStatus = when (node) {
        is CaseNode -> statuses[node.key]?.values?.let { TestModel.propagate(it) } ?: TestStatus.NONE
        else -> {
            val children = node.children.map { status(it) }
            if (children.all { it == TestStatus.NONE }) TestStatus.NONE else TestModel.propagate(children)
        }
    }

    /** The project [case] last ran in, if known. */
    fun lastProject(case: CaseNode): String? = lastProject[case.key]

    /** Forgets every status. */
    fun clear() {
        statuses.clear()
        lastProject.clear()
        publish()
    }

    private fun enqueue(proxy: SMTestProxy, status: TestStatus) {
        if (proxy.isSuite || !proxy.isLeaf) return
        val root = playwrightRoot(proxy) ?: return
        events.trySend(TestEvent(proxy, root, status))
    }

    private suspend fun handle(event: TestEvent) {
        val model = TestDiscovery.getInstance(project).state().model ?: return
        val case = resolve(event.proxy, model) ?: return
        val projectName = projectOf(event.proxy, case)
        val perProject = statuses.getOrPut(case.key) { ConcurrentHashMap() }
        perProject[projectName] = event.status
        lastProject[case.key] = projectName

        val run = runs.getOrPut(event.root) { RunInfo() }
        if (event.status == TestStatus.RUNNING) {
            run.running.add(case.key to projectName)
        } else {
            run.running.remove(case.key to projectName)
            run.finished[case.key] = case
            run.finishedProject[case.key] = projectName
        }
        publish()
    }

    /**
     * Tests still RUNNING when the run ends were interrupted: back to NONE. When the run ran one test, it is
     * published as selected so its trace can be shown (playwright-vscode `_showTraceOnTestProgress` for a single
     * enqueued test).
     */
    private fun finishRun(root: SMTestProxy.SMRootTestProxy) {
        val run = runs.remove(root) ?: return
        for ((key, projectName) in run.running) {
            val perProject = statuses[key] ?: continue
            if (perProject[projectName] == TestStatus.RUNNING) perProject.remove(projectName)
        }
        publish()
        val single = run.finished.values.singleOrNull() ?: return
        val status = statuses[single.key]?.get(run.finishedProject[single.key])
        if (status != TestStatus.PASSED && status != TestStatus.FAILED) return
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(PwTopics.TEST_SELECTED)
                .testSelected(single.file, single.line, single.titlePath, run.finishedProject[single.key])
        }
    }

    private class Resolved(val path: String, val startLine: Int, val endLine: Int)

    private suspend fun resolve(proxy: SMTestProxy, model: TestModel): CaseNode? {
        val resolved = smartReadAction(project) {
            val element = proxy.getLocation(project, GlobalSearchScope.projectScope(project))?.psiElement
                ?: return@smartReadAction null
            val file = (element as? PsiFile)?.virtualFile ?: element.containingFile?.virtualFile
                ?: return@smartReadAction null
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null || element is PsiFile) return@smartReadAction Resolved(file.path, 0, 0)
            val call = TestPsi.enclosingTestCall(element)
            val range = call?.textRange ?: element.textRange
            val start = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength)) + 1
            val end = document.getLineNumber(range.endOffset.coerceIn(0, document.textLength)) + 1
            Resolved(file.path, start, if (call != null) end else start)
        } ?: return null

        val cases = model.casesInFile(resolved.path)
        if (cases.isEmpty()) return null
        if (resolved.startLine > 0) {
            cases.firstOrNull { it.line == resolved.startLine }?.let { return it }
            cases.filter { it.line in resolved.startLine..resolved.endLine }
                .minByOrNull { abs(it.line - resolved.startLine) }
                ?.let { return it }
        }
        return cases.filter { it.title == proxy.name }.singleOrNull()
    }

    /** The ancestor proxy named like one of the case's projects, else the case's only project, else "". */
    private fun projectOf(proxy: SMTestProxy, case: CaseNode): String {
        var parent = proxy.parent
        while (parent != null && parent !is SMTestProxy.SMRootTestProxy) {
            if (parent.name in case.projects) return parent.name
            parent = parent.parent
        }
        return case.projects.singleOrNull() ?: ""
    }

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(TestTopics.RESULTS_CHANGED).testResultsChanged()
    }

    private inner class Listener : SMTRunnerEventsAdapter() {
        override fun onTestStarted(test: SMTestProxy) = enqueue(test, TestStatus.RUNNING)

        override fun onTestFailed(test: SMTestProxy) = enqueue(test, TestStatus.FAILED)

        override fun onTestIgnored(test: SMTestProxy) = enqueue(test, TestStatus.SKIPPED)

        override fun onTestFinished(test: SMTestProxy) {
            when {
                test.isIgnored -> enqueue(test, TestStatus.SKIPPED)
                test.isDefect -> enqueue(test, TestStatus.FAILED)
                test.isPassed -> enqueue(test, TestStatus.PASSED)
            }
        }

        override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
            if (isPlaywright(testsRoot)) events.trySend(RunFinished(testsRoot))
        }
    }

    companion object {
        private val LOG = logger<TestResults>()

        fun getInstance(project: Project): TestResults = project.service()

        private fun playwrightRoot(proxy: SMTestProxy): SMTestProxy.SMRootTestProxy? =
            proxy.root?.takeIf { isPlaywright(it) }

        /** Same check as PlaywrightRunPatcher.isPlaywright: the run profile's configuration type id. */
        private fun isPlaywright(root: SMTestProxy.SMRootTestProxy): Boolean {
            val configuration = root.testConsoleProperties?.configuration as? RunConfiguration ?: return false
            return configuration.type.id == PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID
        }
    }
}
