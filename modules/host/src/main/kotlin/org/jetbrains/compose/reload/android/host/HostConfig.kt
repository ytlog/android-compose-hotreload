package org.jetbrains.compose.reload.android.host

import java.io.File
import java.util.Properties

/**
 * Android specific launch parameters, written by the Gradle plugin.
 * Everything else is read from the upstream 'HotReloadEnvironment' (system properties provided by 'withComposeHotReload').
 */
internal class HostConfig(
    val adb: File,
    val serial: String?,
    val applicationId: String,
    val minSdk: Int,
    /** android.jar (+ optional libraries) */
    val bootClasspath: List<File>,
    /** The runtime classpath of the installed application (project classes and library classes) */
    val applicationClasspath: List<File>,
    val resourceBundle: File,
) {
    companion object {
        const val CONFIG_FILE_PROPERTY = "compose.reload.android.config"

        fun load(): HostConfig {
            val path = System.getProperty(CONFIG_FILE_PROPERTY)
                ?: error("Missing system property '$CONFIG_FILE_PROPERTY'")
            val properties = Properties()
            File(path).inputStream().use { properties.load(it) }
            fun string(key: String) = properties.getProperty(key) ?: error("Missing '$key' in '$path'")
            fun files(key: String) = string(key).split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
            return HostConfig(
                adb = File(string("adb")),
                serial = properties.getProperty("serial")?.takeIf { it.isNotBlank() },
                applicationId = string("applicationId"),
                minSdk = string("minSdk").toInt(),
                bootClasspath = files("bootClasspath"),
                applicationClasspath = files("applicationClasspath"),
                resourceBundle = File(string("resourceBundle")),
            )
        }
    }
}
