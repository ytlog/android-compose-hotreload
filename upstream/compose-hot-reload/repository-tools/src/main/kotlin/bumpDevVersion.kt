import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.jetbrains.kotlin.tooling.core.buildNumber
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

fun main() {
    ensureCleanWorkingDirectory()

    val latestDevVersion = fetchLatestVersionFromFireworkDevRepository()
    val latestDevVersionBuildNumber = latestDevVersion.buildNumber ?: error("Missing build number in version $latestDevVersion")
    val currentVersion = ComposeHotReloadVersion(readGradleProperties("version"))
    val currentBuildNumber = currentVersion.buildNumber ?: error("Missing build number in version $currentVersion")
    val newBuildNumber = maxOf(currentBuildNumber, latestDevVersionBuildNumber) + 1
    val newVersion = currentVersion.withBuildNumber(newBuildNumber)

    writeGradleProperties("version", newVersion.toString())

    command("./gradlew", "updateVersions")

    command("git", "add", ".")
    command("git", "commit", "-m", "v$newVersion")
    command("git", "tag", "v$newVersion")
}


private fun fetchLatestVersionFromFireworkDevRepository(): ComposeHotReloadVersion {
    val mavenMetadataUrl =
        "https://packages.jetbrains.team/maven/p/firework/dev/org/jetbrains/compose/hot-reload/core/maven-metadata.xml"

    val mavenMetadata = URI(mavenMetadataUrl).toURL().openStream().use { inputStream ->
        inputStream.readAllBytes().decodeToString()
    }

    val rawVersion = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mavenMetadata.byteInputStream())
        .getElementsByTagName("latest").item(0).textContent

    return ComposeHotReloadVersion(rawVersion)
}

private class ComposeHotReloadVersion(val value: String) {
    companion object {
        val regex = Regex(
            """(?<major>\d+).(?<minor>\d+).(?<patch>\d+)""" +
                """(-(?<qualifier>[\w.]+\d*))?""" +
                """(([-+])(?<buildNumber>\d+))?"""
        )
    }

    val match = regex.matchEntire(value) ?: error("Invalid version '$value'")
    val major = match.groups["major"]!!.value.toInt()
    val minor = match.groups["minor"]!!.value.toInt()
    val patch = match.groups["patch"]!!.value.toInt()
    val qualifier = match.groups["qualifier"]?.value
    val buildNumber = match.groups["buildNumber"]?.value?.toInt()

    fun withBuildNumber(buildNumber: Int?) = ComposeHotReloadVersion(
        "$major.$minor.$patch" +
            (qualifier?.let { "-$it" } ?: "") +
            (buildNumber?.let { "+$it" } ?: "")
    )

    override fun toString(): String {
        return value
    }
}
