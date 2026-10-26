pluginManagement {
    repositories {
        // STAGING_REPOSITORY
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/firework/dev") {
            content { includeGroupAndSubgroups("org.jetbrains.compose.hot-reload") }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // STAGING_REPOSITORY
        google()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/firework/dev") {
            content { includeGroupAndSubgroups("org.jetbrains.compose.hot-reload") }
        }
    }
}
rootProject.name = "published-consumer"
include(":app")
