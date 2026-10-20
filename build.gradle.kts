import java.util.Properties

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val upstreamDirectory = layout.projectDirectory.dir("upstream/compose-hot-reload")
val upstreamVersion = libs.versions.upstream.get()
val upstreamLock = Properties().apply {
    file("upstream/lock.properties").inputStream().use { load(it) }
}
val upstreamProperties = Properties().apply {
    upstreamDirectory.file("gradle.properties").asFile.inputStream().use { load(it) }
}
check(upstreamLock.getProperty("version") == upstreamVersion &&
    upstreamProperties.getProperty("version") == upstreamVersion) {
    "Upstream version mismatch: update upstream/lock.properties, the version catalog and the upstream checkout together"
}

subprojects {
    group = "io.github.ytlog.compose.hot-reload"
    version = rootProject.version
}

val upstreamModules = listOf(
    "hot-reload-core", "hot-reload-analysis", "hot-reload-agent", "hot-reload-orchestration",
    "hot-reload-gradle-core", "hot-reload-gradle-idea", "hot-reload-gradle-plugin",
    "hot-reload-devtools", "hot-reload-devtools-api", "hot-reload-runtime-jvm",
)
val upstreamTasks = upstreamModules.map { ":$it:publishAllPublicationsToLocalRepository" } + listOf(
    ":hot-reload-annotations:publishJvmPublicationToLocalRepository",
    ":hot-reload-annotations:publishKotlinMultiplatformPublicationToLocalRepository",
    ":hot-reload-runtime-api:publishJvmPublicationToLocalRepository",
    ":hot-reload-runtime-api:publishKotlinMultiplatformPublicationToLocalRepository",
    ":hot-reload-test:core:publishAllPublicationsToLocalRepository",
)
val sdkDirectory = providers.environmentVariable("ANDROID_HOME").orNull
    ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: file("local.properties").takeIf { it.isFile }?.let { local ->
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }

tasks.register<Exec>("prepareUpstream") {
    group = "build setup"
    description = "Builds the pinned upstream JVM artifacts required by Android, including devtools"
    workingDir(upstreamDirectory)
    val wrapper = if (System.getProperty("os.name").startsWith("Windows"))
        listOf("cmd", "/c", "gradlew.bat") else listOf("./gradlew")
    commandLine(wrapper + listOf("--console=plain") + upstreamTasks)
    if (sdkDirectory != null) environment("ANDROID_HOME", sdkDirectory)
}

val verifyUpstream = tasks.register("verifyUpstream") {
    group = "verification"
    description = "Checks that the pinned upstream artifacts have been prepared"
    val repository = upstreamDirectory.dir("build/repo/org/jetbrains/compose/hot-reload")
    val modules = upstreamModules + listOf("hot-reload-annotations-jvm", "hot-reload-runtime-api-jvm", "hot-reload-test-core")
    val poms = modules.map { repository.file("$it/$upstreamVersion/$it-$upstreamVersion.pom").asFile }
    doLast {
        val missing = poms.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Missing upstream artifacts. Run './gradlew prepareUpstream' in the repository root. Missing: " +
                missing.joinToString { it.parentFile.parentFile.name }
        }
    }
}

subprojects {
    tasks.matching { it.name.startsWith("compile") }.configureEach { dependsOn(verifyUpstream) }
}

tasks.register("androidCheck") {
    group = "verification"
    description = "Runs Android protocol/host tests, validates the plugin and builds the device runtime"
    dependsOn(
        ":hot-reload-android-core:test",
        ":hot-reload-android-host:test",
        ":hot-reload-android-gradle-plugin:validatePlugins",
        ":hot-reload-android-runtime:assembleDebug",
    )
}

tasks.register<Delete>("clean") { delete(layout.buildDirectory) }
