/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import dev.uzi.pw.core.NodeRunner
import dev.uzi.pw.core.PwException
import java.io.File
import java.nio.charset.StandardCharsets

/** The project's package manager, detected from its lockfile, and the command that adds dev dependencies. */
enum class PackageManager(val binary: String, private val addDev: List<String>, private val lockfiles: List<String>) {
    PNPM("pnpm", listOf("add", "-D"), listOf("pnpm-lock.yaml")),
    YARN("yarn", listOf("add", "-D"), listOf("yarn.lock")),
    BUN("bun", listOf("add", "-d"), listOf("bun.lock", "bun.lockb")),
    NPM("npm", listOf("install", "-D"), listOf("package-lock.json"));

    /** `<pm> add -D <packages>` in [workingDir]. Looks for the binary next to the project's node, then on PATH. */
    fun addDevCommand(project: Project, workingDir: VirtualFile, packages: List<String>): GeneralCommandLine {
        val exe = find(project) ?: throw PwException("$binary was not found next to the Node.js interpreter or on PATH.")
        return GeneralCommandLine(listOf(exe) + addDev + packages)
            .withWorkingDirectory(workingDir.toNioPath())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
    }

    fun commandText(packages: List<String>): String = (listOf(binary) + addDev + packages).joinToString(" ")

    private fun find(project: Project): String? {
        val names = if (SystemInfo.isWindows) listOf("$binary.cmd", "$binary.exe", binary) else listOf(binary)
        val nodeDir = NodeRunner.getInstance(project).nodePath()?.let { File(it).parentFile }
        if (nodeDir != null) {
            names.map { File(nodeDir, it) }.firstOrNull { it.isFile }?.let { return it.absolutePath }
        }
        for (name in names) {
            val found = if (SystemInfo.isWindows) PathEnvironmentVariableUtil.findExecutableInWindowsPath(name)
            else PathEnvironmentVariableUtil.findInPath(name)?.absolutePath
            if (found != null && File(found).isFile) return found
        }
        return null
    }

    companion object {
        /** The package manager whose lockfile is nearest to [dir], walking up; npm when none is found. */
        fun detect(dir: VirtualFile): PackageManager {
            var current: VirtualFile? = dir
            while (current != null) {
                for (pm in entries) {
                    if (pm.lockfiles.any { current.findChild(it) != null }) return pm
                }
                current = current.parent
            }
            return NPM
        }
    }
}
