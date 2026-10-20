package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.session.ApplicationIndex

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MutableApplicationInfo
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ApplicationIndexTest {
    @Test
    fun `rejected analysis and overwritten build outputs do not replace the running snapshot`() {
        val directory = Files.createTempDirectory("application-index").toFile()
        val id = ClassId("fixture/Labels")
        fun code(addAbstractMethod: Boolean) = ClassWriter(0).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                id.value, null, "java/lang/Object", null)
            if (addAbstractMethod) visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                "required", "()Ljava/lang/String;", null, null).visitEnd()
            visitEnd()
        }.toByteArray()
        try {
            val original = code(false)
            val file = directory.resolve("Labels.class").apply { writeBytes(original) }
            val index = ApplicationIndex(listOf(directory), emptyList())
            val rejected = code(true)
            file.writeBytes(rejected)
            val attempt = MutableApplicationInfo()
            index.enqueueRedefinition(id, rejected, attempt)

            assertContentEquals(original, index.bytecode(id))
            assertTrue(attempt.methodIndex.keys.any { it.methodName == "required" })
            assertTrue(index.currentApplicationInfo.methodIndex.isEmpty())
            assertTrue(index.pendingRedefinitions.classIndex.isEmpty())

            file.writeBytes(original)
            assertContentEquals(file.readBytes(), index.bytecode(id))
        } finally {
            directory.deleteRecursively()
        }
    }
}
