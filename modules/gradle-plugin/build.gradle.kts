/*
 * Gradle plugin for Android applications.
 * Registers the upstream tasks ('ComposeHotSnapshotTask', 'ComposeHotReloadTask') for debuggable Android variants
 * and launches the host ('hot-reload-android-host') with the upstream 'withComposeHotReload' arguments.
 */
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        /* AGP 8 requires Java 17 */
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        /* Runs inside Gradle (embedded Kotlin) */
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        optIn.addAll(
            "org.jetbrains.compose.reload.InternalHotReloadApi",
            "org.jetbrains.compose.reload.DelicateHotReloadApi",
        )
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

val upstreamVersion = libs.versions.upstream.get()

/* Coordinates of the runtime artifacts added by the plugin, generated into the plugin */
val generateBuildConfig by tasks.registering {
    val output = layout.buildDirectory.dir("generated/buildConfig")
    val version = project.version.toString()
    val group = project.group.toString()
    inputs.property("version", version)
    inputs.property("group", group)
    outputs.dir(output)
    doLast {
        output.get().file("org/jetbrains/compose/reload/android/gradle/BuildConfig.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                |package org.jetbrains.compose.reload.android.gradle
                |
                |internal object BuildConfig {
                |    const val GROUP = "$group"
                |    const val VERSION = "$version"
                |}
                |""".trimMargin()
            )
        }
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateBuildConfig)
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)

    /* Upstream Gradle plugin (self-contained: embeds core, orchestration and gradle-core) */
    implementation("org.jetbrains.compose.hot-reload:hot-reload-gradle-plugin:$upstreamVersion")
    /* Required by the upstream plugin at runtime (devtools configuration) */
    implementation(libs.compose.gradlePlugin)

    compileOnly(libs.asm.core)
}

gradlePlugin {
    plugins.create("android") {
        id = "io.github.ytlog.compose.hot-reload.android"
        implementationClass = "org.jetbrains.compose.reload.android.gradle.ComposeHotReloadAndroidPlugin"
    }
}
