package org.jetbrains.compose.reload.android.host.session

import org.jetbrains.compose.reload.android.protocol.Protocol
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Retain installed metadata, never the installed APK or decompressed resource contents. */
internal class ResourceIndex(private val bundle: File) {
    private val installed = bundle.inputStream().buffered().use { Snapshot.read(it) }
    private var appliedDigest = installed.digest

    fun pending(): ByteArray? {
        // Most code edits leave resources unchanged. Do not inflate or allocate that APK again.
        val observedDigest = bundle.inputStream().buffered().use(::digest)
        if (observedDigest == appliedDigest) return null
        check(bundle.length() <= Protocol.MAX_FRAME_SIZE) { "Resource APK exceeds reload payload limit; reinstall with hotRun" }
        val bytes = bundle.inputStream().use { it.readNBytes(Protocol.MAX_FRAME_SIZE + 1) }
        check(bytes.size <= Protocol.MAX_FRAME_SIZE) { "Resource APK exceeds reload payload limit; reinstall with hotRun" }
        val next = Snapshot.read(bytes.inputStream())
        check(next.digest == observedDigest) { "Resource APK changed while preparing reload; retry after the build finishes" }
        check(next.manifest == installed.manifest) {
            "AndroidManifest.xml changed; reinstall with hotRun before reloading code or resources"
        }
        check(next.identities == installed.identities) {
            "Resource names or IDs changed; resource addition/removal requires reinstalling with hotRun"
        }
        check(next.paths.containsAll(installed.paths)) {
            "Installed resource/asset files were removed; reinstall with hotRun (overlays cannot hide installed files)"
        }
        check(next.configurations.containsAll(installed.configurations)) {
            "Installed resource configurations were removed; reinstall with hotRun (overlays cannot hide installed qualifiers)"
        }
        return bytes
    }

    /** Call only after a successful device transaction; rejected changes must remain retryable. */
    fun commit(bytes: ByteArray) {
        appliedDigest = bytes.inputStream().use(::digest)
    }

    private class Snapshot(
        val digest: List<Byte>, val manifest: List<Byte>, val identities: List<Byte>,
        val paths: Set<String>, val configurations: Set<String>,
    ) {
        companion object {
            fun read(input: InputStream): Snapshot {
                val hash = MessageDigest.getInstance("SHA-256")
                val raw = DigestInputStream(input, hash)
                return ZipInputStream(raw).use { zip ->
                    var manifest: List<Byte>? = null
                    var identities: List<Byte>? = null
                    var configurations: Set<String>? = null
                    val paths = mutableSetOf<String>()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when (entry.name) {
                            "AndroidManifest.xml" -> manifest = digest(zip)
                            "META-INF/compose-hot-reload/resource-ids.txt" -> identities = digest(zip)
                            "META-INF/compose-hot-reload/configurations.txt" -> configurations =
                                zip.bufferedReader(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toSet()
                        }
                        if (entry.name.startsWith("res/") || entry.name.startsWith("assets/")) paths += entry.name
                        // Unneeded resource entries are drained with ZipInputStream's fixed buffer.
                        zip.closeEntry()
                    }
                    // ZipInputStream may stop before the central directory/trailer; hash the whole file.
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (raw.read(buffer) != -1) { }
                    Snapshot(hash.digest().toList(),
                        checkNotNull(manifest) { "Missing 'AndroidManifest.xml' in resource snapshot" },
                        checkNotNull(identities) { "Missing resource IDs in resource snapshot" }, paths,
                        checkNotNull(configurations) { "Missing resource configurations in resource snapshot" })
                }
            }
        }
    }
}

private fun digest(input: InputStream): List<Byte> {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        hash.update(buffer, 0, count)
    }
    return hash.digest().toList()
}
