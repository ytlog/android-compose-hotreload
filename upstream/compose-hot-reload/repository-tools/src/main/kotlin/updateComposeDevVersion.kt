import kotlin.io.path.Path
import kotlin.io.path.readText

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

private val composeDevVersionRegex = Regex(".*\\+dev.\\d+")

fun main() {
    val process = ProcessBuilder.startPipeline(
        listOf(
            ProcessBuilder("curl", "-s", "https://api.github.com/repos/JetBrains/compose-multiplatform/tags"),
            ProcessBuilder("jq", "-r", ".[].name").redirectError(ProcessBuilder.Redirect.INHERIT)
        )
    ).last()

    val versions = process.inputStream.reader().readText().lines()
        .map { it.trim().removePrefix("v") }
        .filter { it.isNotEmpty() }

    if (process.waitFor() != 0) error("Failed to fetch tags")

    val latestDevVersion = versions.first { composeDevVersionRegex.matches(it) }
    val testDimensions = Path(".teamcity/testDimensions.json")
    val testDimensionsContent = testDimensions.readText()

    versions.forEach { version ->
        if (version.matches(composeDevVersionRegex) && version in testDimensionsContent) {
            if (version == latestDevVersion) {
                println("Latest dev version '$latestDevVersion' is UP-TO-DATE")
                return
            }
            
            println("Replacing '$version' with '$latestDevVersion")
            ensureCleanWorkingDirectory()

            testDimensions.toFile().writeText(testDimensionsContent.replace(version, latestDevVersion))
            command("git", "add", ".")
            command("git", "commit", "-m", "Update Compose dev version to '$latestDevVersion'")
            return
        }
    }

    error("Cannot update Compose dev version; No known dev versions found in '${testDimensions.fileName}'")

}
