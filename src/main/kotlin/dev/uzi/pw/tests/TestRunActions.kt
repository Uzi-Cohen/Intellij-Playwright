/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.tests

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.uzi.pw.PwBundle
import dev.uzi.pw.agents.TestPsi
import dev.uzi.pw.core.PlaywrightProjectLocator
import dev.uzi.pw.core.PwNotifications
import dev.uzi.pw.core.PwProjectScope
import dev.uzi.pw.run.PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs or debugs a test tree node through the bundled Playwright run configuration, the way the editor's
 * "Run" context action does (BaseRunConfigurationAction / RunContextAction):
 *
 * 1. A [ConfigurationContext] for the node's PSI element: the file for a file node, the `test(...)` or
 *    `test.describe(...)` call at the listed line for a test or describe node; for "all tests" the config file,
 *    then the common testDir, then the config's directory.
 * 2. [ConfigurationContext.findExisting] when it is a Playwright configuration, else the first Playwright
 *    configuration from [ConfigurationContext.getConfigurationsFromContext], added with
 *    [RunManager.setTemporaryConfiguration].
 * 3. [ExecutionUtil.runConfiguration] with the Run or Debug executor.
 *
 * The run then goes through PlaywrightRunPatcher like any other Playwright run.
 */
object TestRunActions {
    private val LOG = logger<TestRunActions>()

    /** Runs (or debugs) [node]; null runs every test of the selected config. Returns at once. */
    fun run(project: Project, node: TestNode?, debug: Boolean) {
        PwProjectScope.getInstance(project).cs.launch {
            try {
                doRun(project, node, debug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Cannot run Playwright tests", e)
                PwNotifications.error(project, PwBundle.message("tests.run.failed", e.message ?: e.toString()))
            }
        }
    }

    private class Found(
        val context: ConfigurationContext,
        val fromContext: ConfigurationFromContext?,
        val existing: RunnerAndConfigurationSettings?,
    )

    private suspend fun doRun(project: Project, node: TestNode?, debug: Boolean) {
        val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
        val found = smartReadAction(project) { findConfiguration(project, node) }
        if (found == null) {
            PwNotifications.warn(project, PwBundle.message("tests.run.noConfiguration", node?.title ?: PwBundle.message("tests.node.all")))
            return
        }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val existing = found.existing
            if (existing != null) {
                ExecutionUtil.runConfiguration(existing, executor)
                return@withContext
            }
            val fromContext = found.fromContext ?: return@withContext
            fromContext.onFirstRun(found.context) {
                val settings = fromContext.configurationSettings
                RunManager.getInstance(project).setTemporaryConfiguration(settings)
                ExecutionUtil.runConfiguration(settings, executor)
            }
        }
    }

    /** Called under a read action in smart mode. */
    private fun findConfiguration(project: Project, node: TestNode?): Found? {
        for (element in elementsFor(project, node)) {
            val context = ConfigurationContext(element)
            val existing = context.findExisting()?.takeIf { isPlaywright(it.configuration) && acceptable(it.configuration, node) }
            val fromContext = context.configurationsFromContext.orEmpty()
                .firstOrNull { isPlaywright(it.configuration) && acceptable(it.configuration, node) }
            if (existing != null || fromContext != null) return Found(context, fromContext, existing)
        }
        return null
    }

    private fun elementsFor(project: Project, node: TestNode?): List<PsiElement> {
        val psiManager = PsiManager.getInstance(project)
        val fs = LocalFileSystem.getInstance()
        return when (node) {
            null -> {
                val config = PlaywrightProjectLocator.getInstance(project).selectedConfig() ?: return emptyList()
                val root = TestDiscovery.getInstance(project).state().model?.takeIf { it.configPath == config.configFile.path }?.root
                listOfNotNull(
                    psiManager.findFile(config.configFile),
                    root?.let { fs.findFileByPath(it) }?.takeIf { it.isDirectory }?.let { psiManager.findDirectory(it) },
                    config.configFile.parent?.let { psiManager.findDirectory(it) },
                ).distinct()
            }
            is FileNode -> listOfNotNull(fs.findFileByPath(node.file)?.let { psiManager.findFile(it) })
            is DescribeNode -> listOfNotNull(callAt(project, node, test = false))
            is CaseNode -> listOfNotNull(callAt(project, node, test = true))
        }
    }

    /** The `test(...)` (or `test.describe(...)`) call that starts on the node's line. */
    private fun callAt(project: Project, node: TestNode, test: Boolean): PsiElement? {
        val file = LocalFileSystem.getInstance().findFileByPath(node.file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val lineIndex = node.line - 1
        if (lineIndex !in 0 until document.lineCount) return null
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val offset = (lineStart + maxOf(0, node.column - 1)).coerceAtMost(lineEnd)

        fun matches(call: JSCallExpression): Boolean {
            if (document.getLineNumber(call.textRange.startOffset) != lineIndex) return false
            return if (test) TestPsi.isTestCall(call) && !TestPsi.isStepCall(call) else isDescribeCall(call)
        }

        var call = PsiTreeUtil.getParentOfType(psiFile.findElementAt(offset), JSCallExpression::class.java, false)
        while (call != null) {
            if (matches(call)) return call
            call = PsiTreeUtil.getParentOfType(call, JSCallExpression::class.java, true)
        }
        return PsiTreeUtil.findChildrenOfType(psiFile, JSCallExpression::class.java).firstOrNull { matches(it) }
    }

    /** `describe(...)`, `test.describe(...)`, `test.describe.serial|parallel|only|skip|fixme(...)`. */
    private fun isDescribeCall(call: JSCallExpression): Boolean {
        var ref = call.methodExpression as? JSReferenceExpression ?: return false
        while (true) {
            if (ref.referenceName == "describe") return true
            ref = ref.qualifier as? JSReferenceExpression ?: return false
        }
    }

    private fun isPlaywright(configuration: RunConfiguration?): Boolean =
        configuration?.type?.id == PLAYWRIGHT_RUN_CONFIGURATION_TYPE_ID

    /**
     * For "all tests" only a configuration whose scope is ALL or DIRECTORY (read by reflection: the Playwright run
     * configuration classes live in an internal module, see PlaywrightRunPatcher), so a config file or a stray file
     * is never run on its own.
     */
    private fun acceptable(configuration: RunConfiguration, node: TestNode?): Boolean {
        if (node != null) return true
        val kind = try {
            val settings = configuration.javaClass.getMethod("getSettings").invoke(configuration)
            val scope = settings.javaClass.getMethod("getScope").invoke(settings)
            scope.javaClass.getMethod("getKind").invoke(scope)?.toString()
        } catch (e: Exception) {
            LOG.debug("Cannot read the Playwright run scope", e)
            null
        }
        return kind == null || kind == "ALL" || kind == "DIRECTORY"
    }
}
