import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

fun readGradleProperties(): String {
    return gradlePropertiesFile.readText()
}

fun writeGradleProperties(text: String) {
    gradlePropertiesFile.writeText(text)
}

fun readGradleProperties(key: String): String {
    val regex = Regex("""^$key=(?<value>.*)""", RegexOption.MULTILINE)
    val match = regex.find(readGradleProperties()) ?: error("Cannot find '$key' in gradle.properties")
    return match.groups["value"]!!.value
}

fun writeGradleProperties(key: String, value: String) {
    val regex = Regex("""^$key=(?<value>.*)""", RegexOption.MULTILINE)
    val newText = regex.replace(readGradleProperties()) { """$key=$value""" }
    writeGradleProperties(newText)
}
