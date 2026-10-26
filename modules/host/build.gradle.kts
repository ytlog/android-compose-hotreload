/*
 * Host side of Android hot reload: takes the role of the upstream 'hot-reload-agent' for an app running on a device.
 * Runs on the developer machine (launched by the Gradle plugin) and reuses the upstream agent,
 * orchestration and analysis unmodified. The device only executes the resulting redefinitions.
 */
plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "org.jetbrains.compose.reload.InternalHotReloadApi",
            "org.jetbrains.compose.reload.DelicateHotReloadApi",
            "org.jetbrains.compose.reload.ExperimentalHotReloadApi",
        )
    }
}

java {
    withSourcesJar()
}

val upstreamVersion = libs.versions.upstream.get()

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":hot-reload-android-core"))

    /* Pinned upstream artifacts, prepared by the root prepareUpstream task. */
    implementation("org.jetbrains.compose.hot-reload:hot-reload-agent:$upstreamVersion")
    implementation("org.jetbrains.compose.hot-reload:hot-reload-orchestration:$upstreamVersion")
    implementation("org.jetbrains.compose.hot-reload:hot-reload-analysis:$upstreamVersion")
    implementation("org.jetbrains.compose.hot-reload:hot-reload-core:$upstreamVersion")
    implementation(libs.coroutines)
    implementation(libs.javassist)
    implementation(libs.asm.tree)

    /* D8: Converts the redefined classes to DEX */
    implementation(libs.r8)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repository.root", rootProject.projectDir.absolutePath)
}

// A test-only orchestration client makes the real-device tooling workflow reproducible.
val workflowProbeClasspath = sourceSets.test.get().runtimeClasspath
val workflowProbeClasspathFile = layout.buildDirectory.file("workflow-probe/classpath.txt")
tasks.register("prepareWorkflowProbe") {
    group = "verification"
    description = "Prepares the test-only orchestration client used by tools/workflow-test.py"
    dependsOn(tasks.testClasses)
    inputs.files(workflowProbeClasspath)
    outputs.file(workflowProbeClasspathFile)
    doLast {
        workflowProbeClasspathFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(workflowProbeClasspath.asPath)
        }
    }
}
