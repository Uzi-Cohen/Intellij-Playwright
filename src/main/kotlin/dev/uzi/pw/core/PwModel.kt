/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** Minimum Playwright version this plugin supports. */
val MIN_PLAYWRIGHT = PwVersion(1, 56, 0, "1.56.0")

/** A Playwright version parsed from `package.json`. Pre-release suffixes (`-next`, `-beta`, ...) are dropped. */
data class PwVersion(val major: Int, val minor: Int, val patch: Int, val raw: String) : Comparable<PwVersion> {

    override fun compareTo(other: PwVersion): Int =
        compareValuesBy(this, other, PwVersion::major, PwVersion::minor, PwVersion::patch)

    fun atLeast(major: Int, minor: Int): Boolean =
        this.major > major || (this.major == major && this.minor >= minor)

    override fun toString(): String = raw

    companion object {
        private val REGEX = Regex("""^\s*v?(\d+)\.(\d+)(?:\.(\d+))?""")

        fun parse(s: String): PwVersion? {
            val m = REGEX.find(s) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: 0
            return PwVersion(major, minor, patch, s.trim())
        }
    }
}

/**
 * A `playwright.config.*` file and the Playwright installation that serves it.
 *
 * @property workingDir the directory Playwright commands run in: the nearest ancestor of the config
 *   that holds a `package.json`, or the config's own directory.
 * @property packageDir `node_modules/@playwright/test` (or `node_modules/playwright`), when found.
 * @property cliJs `<packageDir>/cli.js`, when found.
 */
data class PwConfig(
    val configFile: VirtualFile,
    val workingDir: VirtualFile,
    val packageDir: Path?,
    val cliJs: Path?,
    val version: PwVersion?,
    val packageName: String?,
) {
    val isSupported: Boolean
        get() = cliJs != null && version != null && version >= MIN_PLAYWRIGHT

    val displayName: String
        get() {
            val base = workingDir.parent
            val path = configFile.path
            return if (base != null && path.startsWith(base.path + "/")) path.substring(base.path.length + 1) else path
        }
}

/** One project of a Playwright config, as reported by `playwright test --list --reporter=json`. */
data class PwProjectInfo(val name: String, val testDir: Path, val outputDir: Path)

class PwException(message: String) : Exception(message)
