/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.trace

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * One `trace.zip` written by Playwright Test.
 *
 * @param path absolute, normalized path of the zip
 * @param mtime last-modified time in epoch millis
 * @param projectName the Playwright project the run belonged to, when it could be told from the folder name
 * @param testName the test's folder name with the project, retry and repeat suffixes removed
 * @param retry the retry index from a `-retryN` suffix, 0 for the first attempt
 */
data class TraceEntry(
    val path: Path,
    val mtime: Long,
    val projectName: String?,
    val testName: String,
    val retry: Int = 0,
)

/**
 * In-memory index of the newest [MAX_ENTRIES] traces of the project. Filled by [TraceWatcher].
 * Thread-safe; all methods may be called from any thread.
 */
@Service(Service.Level.PROJECT)
class TraceIndex {
    private val lock = Any()
    private var entries: List<TraceEntry> = emptyList()

    /** All entries, newest first. */
    fun entries(): List<TraceEntry> = synchronized(lock) { entries }

    /** Entries grouped by test name. Groups are ordered by their newest trace, entries inside a group newest first. */
    fun grouped(): Map<String, List<TraceEntry>> {
        val result = LinkedHashMap<String, MutableList<TraceEntry>>()
        for (entry in entries()) result.getOrPut(entry.testName) { mutableListOf() }.add(entry)
        return result
    }

    fun find(path: Path): TraceEntry? {
        val normalized = path.toAbsolutePath().normalize()
        return entries().firstOrNull { it.path == normalized }
    }

    /** Replaces the whole index. Returns true when the content changed. */
    fun replaceAll(newEntries: Collection<TraceEntry>): Boolean = update { normalize(newEntries) }

    /** Adds or replaces the entry with the same path. Returns true when the content changed. */
    fun add(entry: TraceEntry): Boolean = update { current -> normalize(current.filter { it.path != entry.path } + entry) }

    /** Removes the entries whose path equals [path] or lies under it. Returns true when the content changed. */
    fun remove(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        return update { current -> current.filterNot { it.path.startsWith(normalized) } }
    }

    private inline fun update(transform: (List<TraceEntry>) -> List<TraceEntry>): Boolean = synchronized(lock) {
        val next = transform(entries)
        if (next == entries) return false
        entries = next
        true
    }

    private fun normalize(list: Collection<TraceEntry>): List<TraceEntry> =
        list.distinctBy { it.path }
            .sortedWith(compareByDescending<TraceEntry> { it.mtime }.thenBy { it.path.toString() })
            .take(MAX_ENTRIES)

    companion object {
        const val MAX_ENTRIES: Int = 200

        @JvmStatic
        fun getInstance(project: Project): TraceIndex = project.service()
    }
}
