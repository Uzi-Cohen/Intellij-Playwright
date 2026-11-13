import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "playwright-intellij"

pluginManagement {
    repositories {
        // JetBrains mirror of Maven Central first; Maven Central directly rate-limits CI and sandboxes.
        maven("https://cache-redirector.jetbrains.com/maven-central")
        // Gradle-plugin-only artifacts (foojay-resolver, intellij-platform-gradle-plugin) live only on the portal,
        // so it has to come before Maven Central.
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        // IntelliJ repositories first, so local platform artifacts (bundledPlugin:*) resolve without
        // a round trip to Maven Central.
        intellijPlatform {
            defaultRepositories()
        }
        // JetBrains mirror of Maven Central, above Maven Central itself.
        maven("https://cache-redirector.jetbrains.com/maven-central")
        mavenCentral()
    }
}
