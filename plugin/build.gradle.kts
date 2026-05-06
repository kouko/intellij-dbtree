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

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        create(
            type = IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            version = providers.gradleProperty("platformVersion").get(),
        )
        // Pull the bundled Python plugin's classes onto the compile classpath
        // so PythonSdkResolver can call PythonSdkUtil. Marked optional in
        // plugin.xml so the plugin still loads on Python-less IDEs.
        // ID is "PythonCore" (the bundled Community variant DataSpell ships);
        // it provides the `com.intellij.modules.python` module our optional
        // <depends> entry references.
        bundledPlugin("PythonCore")
    }
}

tasks.test {
    useJUnitPlatform()
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

    // JetBrains Plugin Verifier — checks the built plugin against the target IDE
    // for binary compatibility issues. Runs in the `release.yml` workflow before
    // attaching the .zip to a GitHub Release. Locally: `./gradlew verifyPlugin`.
    // `current()` reuses the IDE pinned in the main dependencies block, so the
    // version is single-sourced from gradle.properties.
    pluginVerification {
        ides {
            current()
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

// ---- Python sidecar (Phase C CLI) integration --------------------------------------
// Bundle ../python-sidecar/src/dbtree_lineage/*.py into the plugin classpath.
// At runtime the plugin extracts these to PathManager.getSystemPath() and runs
// them via the user-configured Python interpreter (which must have sqlglot).

val pythonSidecarSrc = layout.projectDirectory.dir("../python-sidecar/src")

val copyPythonSidecar by tasks.registering(Sync::class) {
    group = "python-sidecar"
    description = "Copy python-sidecar Python source into plugin resources"
    from(pythonSidecarSrc) {
        include("dbtree_lineage/**/*.py")
        exclude("**/__pycache__/**")
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("python-resources"))
}

// Make both synced outputs java source set resource directories.
sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("frontend-resources"))
    resources.srcDir(layout.buildDirectory.dir("python-resources"))
}

tasks.named("processResources") {
    dependsOn(copyFrontendDist, copyPythonSidecar)
}
