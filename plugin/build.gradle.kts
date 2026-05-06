import com.github.gradle.node.pnpm.task.PnpmTask
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("com.github.node-gradle.node")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    intellijPlatform {
        create(
            type = IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            version = providers.gradleProperty("platformVersion").get(),
        )
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("kotlinJvmTarget").get().toInt())
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get()))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("version")
        vendor {
            name = providers.gradleProperty("pluginVendor")
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Leave untilBuild unset — JetBrains recommends this for forward-compatible plugins.
            untilBuild = provider { null }
        }
    }
}

// Auto-open a project in the sandbox IDE so the tool window is reachable.
// Override with `-Pide.project=/path/to/dbt/project` to test against a real
// dbt project (e.g. /tmp/dbt-test/jaffle-shop after `dbt parse`).
tasks.named<JavaExec>("runIde") {
    val projectArg = providers.gradleProperty("ide.project")
        .orElse(rootDir.parentFile.absolutePath)
    args = listOf(projectArg.get())
    systemProperty("idea.plugin.in.sandbox.mode", "true")
}

// ---- Frontend (Phase B React app) integration via node-gradle ----------------------
// Build artifacts from ../frontend land in plugin classpath at lineage-panel-dist/

val frontendDir = layout.projectDirectory.dir("../frontend")
val frontendDistDir = frontendDir.dir("dist")
val frontendResourceDir = "lineage-panel-dist"

node {
    download = true
    version = "22.12.0"
    workDir = layout.buildDirectory.dir("nodejs")
    pnpmWorkDir = layout.buildDirectory.dir("pnpm")
    nodeProjectDir = frontendDir
}

val pnpmInstallFrontend by tasks.registering(PnpmTask::class) {
    group = "frontend"
    description = "pnpm install in ../frontend"
    args = listOf("install", "--frozen-lockfile")
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("pnpm-lock.yaml"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val buildFrontend by tasks.registering(PnpmTask::class) {
    group = "frontend"
    description = "pnpm run build in ../frontend"
    dependsOn(pnpmInstallFrontend)
    args = listOf("run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("pnpm-lock.yaml"))
    inputs.file(frontendDir.file("vite.config.ts"))
    inputs.file(frontendDir.file("tsconfig.json"))
    inputs.file(frontendDir.file("tsconfig.app.json"))
    inputs.file(frontendDir.file("tsconfig.node.json"))
    inputs.file(frontendDir.file("index.html"))
    outputs.dir(frontendDistDir)
}

// Sync the built frontend into the plugin classpath so JCEF can serve it.
val copyFrontendDist by tasks.registering(Sync::class) {
    group = "frontend"
    description = "Copy frontend/dist into plugin resources for JCEF"
    dependsOn(buildFrontend)
    from(frontendDistDir)
    into(layout.buildDirectory.dir("frontend-resources/$frontendResourceDir"))
}

// Make the synced output a Java source set resource directory.
sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("frontend-resources"))
}

tasks.named("processResources") {
    dependsOn(copyFrontendDist)
}
