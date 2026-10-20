package org.jetbrains.compose.reload.android.gradle

import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Uses the public APK artifact so resource merging, qualifiers and library assets stay AGP's responsibility. */
@DisableCachingByDefault(because = "Local development snapshot")
abstract class AndroidResourceSnapshot : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val aapt2: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract var artifactsLoader: BuiltArtifactsLoader

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun snapshot() {
        val artifacts = checkNotNull(artifactsLoader.load(apkDirectory.get())) { "Cannot locate the variant APK" }
        check(artifacts.elements.size == 1 && artifacts.elements.single().filters.isEmpty()) {
            "Resource hot reload requires one universal APK; disable APK splits for this development variant"
        }
        val apk = project.file(artifacts.elements.single().outputFile)
        val dump = ByteArrayOutputStream()
        execOperations.exec {
            it.commandLine(aapt2.get().asFile, "dump", "resources", apk)
            it.standardOutput = dump
        }
        // Include package and resource identities, excluding values/configurations which may change.
        val lines = dump.toString(Charsets.UTF_8).lines()
        val identities = lines.asSequence().map(String::trim)
            .filter { it.startsWith("Package ") || it.startsWith("resource ") }
            .map { it.substringBefore(" PUBLIC") }.sorted().joinToString("\n")
        check(identities.isNotEmpty()) { "aapt2 did not report a resource table for '$apk'" }
        var resource = ""
        val configurations = buildList {
            lines.forEach { line ->
                if (line.trimStart().startsWith("resource ")) resource = line.trim()
                if (line.startsWith("      (") && resource.isNotEmpty()) {
                    add("$resource ${line.trim().substringBefore(')')})")
                }
            }
        }.sorted().joinToString("\n")
        val output = outputDirectory.file(RESOURCE_BUNDLE).get().asFile
        output.parentFile.mkdirs()
        val temporary = output.resolveSibling("${output.name}.tmp")
        try {
            ZipFile(apk).use { source ->
                val stream = CountingOutputStream(temporary.outputStream().buffered())
                ZipOutputStream(stream).use { zip ->
                    fun entry(name: String, bytes: ByteArray, method: Int = ZipEntry.DEFLATED) {
                        zip.putNextEntry(ZipEntry(name).apply {
                            // A DOS-representable date avoids automatic extended timestamp fields.
                            time = 318211200000L
                            this.method = method
                            if (method == ZipEntry.STORED) {
                                size = bytes.size.toLong()
                                compressedSize = size
                                crc = CRC32().apply { update(bytes) }.value
                                // Preserve noCompress/openFd semantics and 4-byte data alignment.
                                val padding = ((4 - (stream.count + 30 + name.toByteArray(Charsets.UTF_8).size) % 4) % 4).toInt()
                                if (padding != 0) extra = ByteArray(4 + padding).apply {
                                    this[0] = 0xff.toByte(); this[1] = 0xff.toByte()
                                    this[2] = padding.toByte()
                                }
                            }
                        })
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    source.entries().asSequence().filter { !it.isDirectory && (
                        it.name == "AndroidManifest.xml" || it.name == "resources.arsc" ||
                            it.name.startsWith("res/") || it.name.startsWith("assets/")) }
                        .sortedBy { it.name }.forEach {
                            entry(it.name, source.getInputStream(it).use { it.readBytes() }, it.method)
                        }
                    entry("META-INF/compose-hot-reload/resource-ids.txt", identities.toByteArray())
                    entry("META-INF/compose-hot-reload/configurations.txt", configurations.toByteArray())
                }
            }
            // Do not touch the timestamp for code-only builds (also avoids continuous-build loops).
            if (!output.exists() || !output.readBytes().contentEquals(temporary.readBytes())) {
                temporary.copyTo(output, overwrite = true)
            }
        } finally {
            temporary.delete()
        }
    }
}

internal const val RESOURCE_BUNDLE = "android-resources.chr"

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set
    override fun write(value: Int) { out.write(value); count++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count += length
    }
}
