package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.android.host.reload.ReloadAttempt
import org.jetbrains.compose.reload.android.host.session.ApplicationIndex
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ReloadAttemptTest {
    private val id = ClassId("fixture/Labels")

    private fun bytecode(withMethod: Boolean) = ClassWriter(0).apply {
        visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            id.value, null, "java/lang/Object", null)
        if (withMethod) visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "required", "()Ljava/lang/String;", null, null).visitEnd()
        visitEnd()
    }.toByteArray()

    private fun withIndex(block: (ApplicationIndex) -> Unit) {
        val directory = Files.createTempDirectory("reload-attempt").toFile()
        try {
            directory.resolve("Labels.class").writeBytes(bytecode(false))
            block(ApplicationIndex(listOf(directory), emptyList()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `discarding an attempt does not leak rejected analysis into a retry`() = withIndex { index ->
        val rejected = ReloadAttempt(index)
        rejected.enqueue(id, bytecode(true))
        assertTrue(rejected.pending.methodIndex.keys.any { it.methodName == "required" })

        val retry = ReloadAttempt(index)
        assertTrue(retry.pending.classIndex.isEmpty())
        assertTrue(index.currentApplicationInfo.methodIndex.isEmpty())
    }

    @Test
    fun `applied statics-only definitions survive until an explicit successful commit`() = withIndex { index ->
        index.enqueueRedefinition(id, bytecode(true))
        val attempt = ReloadAttempt(index)
        assertTrue(attempt.pending.methodIndex.keys.any { it.methodName == "required" })
        assertTrue(index.currentApplicationInfo.methodIndex.isEmpty())

        attempt.commit()
        assertTrue(index.currentApplicationInfo.methodIndex.keys.any { it.methodName == "required" })
        assertTrue(index.pendingRedefinitions.classIndex.isEmpty())
    }
}
