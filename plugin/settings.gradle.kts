import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "intellij-dbtree"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
        id("com.github.node-gradle.node") version "7.1.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()

        intellijPlatform {
            defaultRepositories()
        }
    }
}
