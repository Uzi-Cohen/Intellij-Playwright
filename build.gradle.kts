import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2026.1 bundles stdlib 2.3.20; target the lowest supported platform.
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("JavaScript")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
            create(IntelliJPlatformType.WebStorm, "2026.2.4")
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}
