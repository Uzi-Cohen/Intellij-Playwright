/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.recorder

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/** Creates `tests/recorded-N.spec.ts` under a project's testDir and opens it. Call on the EDT. */
object NewTestFile {
    const val PLACEHOLDER = "// Recording..."

    class Created(val file: VirtualFile, val editor: Editor, val placeholderStart: Int, val placeholderEnd: Int)

    /** The directory new tests go into: `<testDir>/tests`, or testDir itself when it is already named `tests`. */
    fun targetDir(testDir: Path): Path =
        if (testDir.fileName?.toString() == "tests") testDir else testDir.resolve("tests")

    fun nextFreePath(dir: Path): Path? =
        (1 until 100).map { dir.resolve("recorded-$it.spec.ts") }.firstOrNull { !Files.exists(it) }

    fun content(url: String?): String = buildString {
        append("import { test, expect } from '@playwright/test';\n\n")
        append("test('test', async ({ page }) => {\n")
        if (!url.isNullOrBlank()) append("  await page.goto(").append(jsString(url)).append(");\n")
        append("  ").append(PLACEHOLDER).append("\n")
        append("});\n")
    }

    fun create(project: Project, testDir: Path, url: String?): Created? {
        val dir = targetDir(testDir)
        val path = nextFreePath(dir) ?: return null
        val text = content(url)
        val file = WriteCommandAction.writeCommandAction(project)
            .withName("Playwright: new recorded test")
            .compute<VirtualFile, java.io.IOException> {
                val vDir = VfsUtil.createDirectoryIfMissing(dir.toString())
                    ?: throw java.io.IOException("Cannot create $dir")
                val f = vDir.createChildData(NewTestFile, path.fileName.toString())
                VfsUtil.saveText(f, text)
                f
            }
        val start = text.indexOf(PLACEHOLDER)
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, start), true) ?: return null
        return Created(file, editor, start, start + PLACEHOLDER.length)
    }

    /** Single-quoted JS string literal. */
    fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
}
