/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.core

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path

/** Copies files shipped under `src/main/resources/js/` to the IDE system dir so Node can load them. */
object PluginResources {

    /** Extracts `js/[name]` (rewritten only when its content changed) and returns its path. */
    @Synchronized
    fun extractJs(name: String): Path {
        val bytes = PluginResources::class.java.getResourceAsStream("/js/$name")?.use { it.readBytes() }
            ?: throw PwException("Missing plugin resource js/$name")
        val dir = Path.of(PathManager.getSystemPath(), "pw-toolkit", "js")
        Files.createDirectories(dir)
        val target = dir.resolve(name)
        if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
            Files.write(target, bytes)
        }
        return target
    }
}
