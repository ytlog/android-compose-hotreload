package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.session.ResourceIndex
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ResourceIndexTest {
    private fun withBundle(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("resource-index").toFile()
        try { block(directory.resolve("resources.chr")) } finally { directory.deleteRecursively() }
    }

    private fun File.snapshot(value: String = "initial", ids: String = "0x7f010001 string/title",
                              manifest: String = "manifest", asset: Boolean = true,
                              configurations: String = "string/title ()\nstring/title (en)") {
        ZipOutputStream(outputStream()).use { zip ->
            val entries = linkedMapOf("resources.arsc" to value, "AndroidManifest.xml" to manifest,
                "META-INF/compose-hot-reload/resource-ids.txt" to ids,
                "META-INF/compose-hot-reload/configurations.txt" to configurations)
            if (asset) entries["assets/text.txt"] = value
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test fun `content updates stay pending until device acknowledgement including retry and revert`() = withBundle { file ->
        file.snapshot()
        val index = ResourceIndex(file)
        assertNull(index.pending())
        file.snapshot("changed")
        val update = assertNotNull(index.pending())
        assertContentEquals(update, index.pending())
        index.commit(update)
        assertNull(index.pending())
        file.snapshot()
        assertNotNull(index.pending()) // Reverting applied values is also an update.
    }

    @Test fun `resource identity changes reject the whole attempt and reverting recovers`() = withBundle { file ->
        file.snapshot()
        val index = ResourceIndex(file)
        file.snapshot(ids = "0x7f010002 string/title")
        assertContains(assertFailsWith<IllegalStateException> { index.pending() }.message!!, "IDs changed")
        file.snapshot("fixed")
        assertNotNull(index.pending())
    }

    @Test fun `manifest changes require install even when resources are unchanged`() = withBundle { file ->
        file.snapshot()
        val index = ResourceIndex(file)
        file.snapshot(manifest = "new activity")
        assertContains(assertFailsWith<IllegalStateException> { index.pending() }.message!!, "AndroidManifest.xml changed")
    }

    @Test fun `deletions cannot silently fall back to installed assets`() = withBundle { file ->
        file.snapshot()
        val index = ResourceIndex(file)
        file.snapshot(asset = false)
        assertContains(assertFailsWith<IllegalStateException> { index.pending() }.message!!, "files were removed")
    }

    @Test fun `removing a values qualifier requires reinstall`() = withBundle { file ->
        file.snapshot()
        val index = ResourceIndex(file)
        file.snapshot(configurations = "string/title ()")
        assertContains(assertFailsWith<IllegalStateException> { index.pending() }.message!!, "configurations were removed")
    }
}
