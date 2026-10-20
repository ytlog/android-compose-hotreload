import org.jetbrains.compose.reload.core.copyRecursivelyToZip
import org.jetbrains.compose.reload.core.unzipTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeToOrNull
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

class ZipTest {

    @TempDir
    lateinit var inputs: Path

    @TempDir
    lateinit var zips: Path

    @TempDir
    lateinit var outputs: Path

    @Test
    fun `create zip`() {
        val a = inputs.resolve("test/a.txt")
        val b = inputs.resolve("test/b.txt")
        a.createParentDirectories().writeText("A")
        b.createParentDirectories().writeText("B")

        val zip = zips.resolve("test.zip")
        inputs.copyRecursivelyToZip(zip)
        if (!zip.exists()) error("${zip.toUri().toURL()} does not exist")

        zip.unzipTo(outputs)
        val aAfter = outputs.resolve("test/a.txt")
        val bAfter = outputs.resolve("test/b.txt")

        assertEquals(a.readText(), aAfter.readText())
        assertEquals(b.readText(), bAfter.readText())
        assertEquals(
            setOf("test/a.txt", "test/b.txt"),
            outputs.walk().map { it.relativeToOrNull(outputs)?.invariantSeparatorsPathString }.toSet()
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test - overwrite`() {
        val a = inputs.resolve("test/a.txt")
        a.createParentDirectories().writeText("A")
        val zip = zips.resolve("test.zip")
        inputs.copyRecursivelyToZip(zip, overwrite = true)
        zip.unzipTo(outputs)
        assertEquals("A", outputs.resolve("test/a.txt").readText())

        outputs.resolve("test").deleteRecursively()

        a.writeText("Foo")
        inputs.copyRecursivelyToZip(zip, overwrite = true)
        zip.unzipTo(outputs)
        assertEquals("Foo", outputs.resolve("test/a.txt").readText())
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `test - dont overwrite`() {
        val a = inputs.resolve("test/a.txt")
        a.createParentDirectories().writeText("A")

        val zip = zips.resolve("test.zip")
        inputs.copyRecursivelyToZip(zip, overwrite = false)
        assertFailsWith<FileAlreadyExistsException> { inputs.copyRecursivelyToZip(zip, overwrite = false) }
    }

}
