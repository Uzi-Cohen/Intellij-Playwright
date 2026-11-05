import org.gradle.jvm.tasks.Jar
import org.jetbrains.changelog.Changelog
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

        // Local variable: the changelog extension is not configuration-cache safe to touch from a task action.
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = "261"
            // Null omits until-build. An empty assignment would fall back to the platform default (261.*),
            // which would hide the plugin from 2026.2 and later.
            untilBuild = provider { null }
        }
    }

    // Secrets are repository Actions secrets. Absent values leave signPlugin/publishPlugin unconfigured,
    // so buildPlugin and verifyPlugin still run locally and on pull requests.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 0.2.0-beta.1 publishes to the "beta" channel; a release version uses the public default channel.
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            val channel = pluginVersion.substringAfter('-', "").substringBefore('.').substringBefore('+')
            listOf(channel.ifEmpty { "default" })
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

// Apache 2.0 requires the license and NOTICE to travel with the distributed plugin.
// jarSearchableOptions is also a Jar; it must stay an index, not a second copy of the license.
tasks.withType<Jar>().configureEach {
    if (name == "jarSearchableOptions") return@configureEach
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.file("NOTICE"))
}
