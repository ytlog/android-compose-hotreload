package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.session.ResourceIndex
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Separate low-heap process: installed bytes and unrelated expanded entries must not be retained. */
object ResourceIndexMemoryProbe {
    @JvmStatic fun main(args: Array<String>) {
        val file = File(args.single())
        val buffer = ByteArray(8192)
        for (stored in listOf(true, false)) {
            val count = if (stored) 40 * 1024 * 1024 else 128 * 1024 * 1024
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                for ((name, value) in mapOf("AndroidManifest.xml" to "manifest",
                    "META-INF/compose-hot-reload/resource-ids.txt" to "ids",
                    "META-INF/compose-hot-reload/configurations.txt" to "assets ()")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                }
                val entry = ZipEntry("assets/large.bin")
                if (stored) {
                    val crc = CRC32()
                    repeat(count / buffer.size) { crc.update(buffer) }
                    entry.method = ZipEntry.STORED; entry.size = count.toLong(); entry.compressedSize = count.toLong(); entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                repeat(count / buffer.size) { zip.write(buffer) }
                zip.closeEntry()
            }
            val index = ResourceIndex(file)
            check(index.pending() == null)
            check(index.pending() == null)
        }
        println("PASS: resource snapshots bounded")
    }
}
