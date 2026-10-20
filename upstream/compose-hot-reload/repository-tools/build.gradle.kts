import java.util.Locale.getDefault

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

plugins {
    kotlin("jvm") version deps.versions.kotlin
}

dependencies {
    implementation(kotlin("tooling-core"))
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootDir.parentFile
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() } + "Kt"
}

tasks.register<JavaExec>("bumpDevVersion")

tasks.register<JavaExec>("bumpBootstrapVersion")

tasks.register<JavaExec>("installAndroidSdk")

tasks.register<JavaExec>("push")

tasks.register<JavaExec>("pushDevVersionTag")

tasks.register<JavaExec>("updateComposeDevVersion")
