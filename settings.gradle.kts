pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "android-compose-hotreload"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Opt in explicitly; never let an unrelated local artifact replace a published engine.
        if (providers.gradleProperty("artDcevmVersion").isPresent) {
            exclusiveContent {
                forRepository { mavenLocal() }
                filter { includeModule("io.github.ytlog", "artdcevm") }
            }
        }
        exclusiveContent {
            forRepository { maven(file("upstream/compose-hot-reload/build/repo")) }
            filter { includeGroupAndSubgroups("org.jetbrains.compose.hot-reload") }
        }
        google()
        mavenCentral()
    }
}

// Project names retain the published Maven coordinates; directories express Android responsibilities.
mapOf(
    "hot-reload-android-core" to "protocol",
    "hot-reload-android-runtime" to "runtime",
    "hot-reload-android-host" to "host",
    "hot-reload-android-gradle-plugin" to "gradle-plugin",
).forEach { (name, directory) ->
    include(":$name")
    project(":$name").projectDir = file("modules/$directory")
}
