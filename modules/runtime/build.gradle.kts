/*
 * Device side of Android hot reload: the in-process part of the upstream agent and 'hot-reload-runtime-jvm'.
 * Executes redefinitions (ART DCEVM + JVMTI), statics re-initialization and Compose invalidation on the UI thread.
 * Only added to debuggable variants by the Gradle plugin.
 */
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.compose")
    `maven-publish`
}

/* The NDK does not ship 'jvmti.h': use the one of the JDK used for the build */
val jvmtiInclude = layout.buildDirectory.dir("generated/jvmti/include")
val jvmtiHeader = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    .map { it.metadata.installationPath.file("include/jvmti.h") }

val copyJvmtiHeader by tasks.registering(Copy::class) {
    from(jvmtiHeader)
    into(jvmtiInclude)
}

android {
    namespace = "org.jetbrains.compose.reload.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        /* ART DCEVM */
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DJVMTI_INCLUDE_DIR=${jvmtiInclude.get().asFile.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures { compose = true }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(copyJvmtiHeader)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        optIn.add("org.jetbrains.compose.reload.InternalHotReloadApi")
    }
}

dependencies {
    api(project(":hot-reload-android-core"))
    implementation(libs.artDcevm)

    /* Provided by the application (a Compose application): never override its versions */
    compileOnly(kotlin("stdlib"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
}

afterEvaluate {
    publishing {
        publications.create<MavenPublication>("release") { from(components["release"]) }
    }
}
