package org.jetbrains.compose.reload.android.host.session

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Resource identities and the installed manifest are fixed for the lifetime of this host. */
internal class ResourceIndex(private val bundle: File) {
    private val installed = Snapshot.read(bundle)
    private var appliedDigest = installed.digest

    fun pending(): ByteArray? {
        val next = Snapshot.read(bundle)
        check(next.manifest.contentEquals(installed.manifest)) {
            "AndroidManifest.xml changed; reinstall with hotRun before reloading code or resources"
        }
        check(next.identities.contentEquals(installed.identities)) {
            "Resource names or IDs changed; resource addition/removal requires reinstalling with hotRun"
        }
        check(next.paths.containsAll(installed.paths)) {
            "Installed resource/asset files were removed; reinstall with hotRun (overlays cannot hide installed files)"
        }
        check(next.configurations.containsAll(installed.configurations)) {
            "Installed resource configurations were removed; reinstall with hotRun (overlays cannot hide installed qualifiers)"
        }
        return next.bytes.takeUnless { next.digest == appliedDigest }
    }

    /** Call only after a successful device transaction; rejected changes must remain retryable. */
    fun commit(bytes: ByteArray) {
        appliedDigest = digest(bytes)
    }

    private class Snapshot(
        val bytes: ByteArray, val manifest: ByteArray, val identities: ByteArray, val paths: Set<String>,
        val configurations: Set<String>,
    ) {
        val digest = digest(bytes)

        companion object {
            fun read(file: File): Snapshot {
                val bytes = file.readBytes()
                return ZipInputStream(bytes.inputStream()).use { zip ->
                    val entries = buildMap {
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            put(entry.name, zip.readBytes())
                        }
                    }
                    fun required(name: String) = checkNotNull(entries[name]) {
                        "Missing '$name' in resource snapshot '$file'"
                    }
                    Snapshot(bytes, required("AndroidManifest.xml"),
                        required("META-INF/compose-hot-reload/resource-ids.txt"),
                        entries.keys.asSequence()
                            .filter { it.startsWith("res/") || it.startsWith("assets/") }.toSet(),
                        required("META-INF/compose-hot-reload/configurations.txt").toString(Charsets.UTF_8)
                            .lines().filter { it.isNotBlank() }.toSet())
                }
            }
        }
    }
}

private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
