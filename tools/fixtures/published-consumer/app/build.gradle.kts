plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("io.github.ytlog.compose.hot-reload.android") version "@VERSION@"
}
android {
    namespace = "dev.compose.hotreload.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.compose.hotreload.sample"
        minSdk = 30
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
}

// Resolve all three consumers, including the desktop devtools used by normal hotRun.
tasks.register("verifyPublishedClasspath") {
    doLast {
        val run = tasks.named("hotRunDebug", org.gradle.api.tasks.JavaExec::class.java).get()
        run.jvmArgumentProviders.forEach { it.asArguments().toList() }
        val files = run.classpath.files + configurations.getByName("debugRuntimeClasspath").files +
            configurations.getByName("composeHotReloadDevTools").files
        val names = files.map { it.name }
        for (artifact in listOf("hot-reload-android-host", "hot-reload-android-core", "hot-reload-android-runtime")) {
            check(names.any { it.startsWith("$artifact-@VERSION@.") }) { "Missing $artifact" }
        }
        check(names.any { it.startsWith("hot-reload-devtools-") }) { "Missing devtools" }
        check(names.any { it.startsWith("artdcevm-0.1.0-alpha.4") }) { "Missing public engine" }
        check(files.none { it.path.contains(".m2/repository") || it.path.contains("upstream/compose-hot-reload/build/repo") })
        layout.buildDirectory.file("published-classpath.txt").get().asFile.apply {
            parentFile.mkdirs()
            writeText(files.joinToString("\n") { it.name })
        }
    }
}
