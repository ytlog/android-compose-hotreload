rootProject.name = "compose-hot-reload-android-sample"

pluginManagement {
    /* Compose Hot Reload for Android (plugin, runtime and host) */
    includeBuild("..")
    repositories {
        maven(file("../upstream/compose-hot-reload/build/repo")) {
            mavenContent { includeGroupAndSubgroups("org.jetbrains.compose.hot-reload") }
        }
        google {
            mavenContent {
                includeGroupByRegex(".*android.*")
                includeGroupByRegex(".*google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Opt in explicitly; never let an unrelated local artifact replace a published engine.
        if (providers.gradleProperty("artDcevmVersion").isPresent) {
            exclusiveContent {
                forRepository { mavenLocal() }
                filter { includeModule("io.github.ytlog", "artdcevm") }
            }
        }
        maven(file("../upstream/compose-hot-reload/build/repo")) {
            mavenContent { includeGroupAndSubgroups("org.jetbrains.compose.hot-reload") }
        }
        google {
            mavenContent {
                includeGroupByRegex(".*android.*")
                includeGroupByRegex(".*androidx.*")
                includeGroupByRegex(".*google.*")
            }
        }
        mavenCentral()
    }
}

includeBuild("..")

include(":lib")
