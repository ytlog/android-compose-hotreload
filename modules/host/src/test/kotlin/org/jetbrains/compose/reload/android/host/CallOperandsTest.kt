package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.android.host.reload.CallOperands
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallOperandsTest {
    private fun operand(merge: Boolean, nullOnly: Boolean = false) = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
        visit(V11, ACC_PUBLIC, "fixture/Operands", null, "java/lang/Object", null)
        visitMethod(ACC_PUBLIC or ACC_STATIC, "create", "(ZLjava/util/function/Supplier;)V", null, null).apply {
            visitCode()
            val alternate = Label(); val join = Label()
            if (merge) { visitVarInsn(ILOAD, 0); visitJumpInsn(IFEQ, alternate) }
            if (nullOnly) visitInsn(ACONST_NULL) else {
                visitTypeInsn(NEW, "fixture/ConcreteSupplier"); visitInsn(DUP)
                visitMethodInsn(INVOKESPECIAL, "fixture/ConcreteSupplier", "<init>", "()V", false)
                visitTypeInsn(CHECKCAST, "java/util/function/Supplier")
            }
            if (merge) { visitJumpInsn(GOTO, join); visitLabel(alternate); visitVarInsn(ALOAD, 1); visitLabel(join) }
            visitMethodInsn(INVOKESTATIC, "library/Consumer", "consume", "(Ljava/util/function/Supplier;)V", false)
            visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray().let { bytes ->
        val node = ClassNode(); ClassReader(bytes).accept(node, 0)
        val method = node.methods.single()
        CallOperands().analyze(MethodId(ClassId(node.name), method.name, method.desc), method).values.single { it.method.methodName == "consume" }
    }

    @Test
    fun `allocation identity survives widening checkcast`() {
        assertEquals(ClassId("fixture/ConcreteSupplier"), operand(false).argumentTypes[0])
    }

    @Test
    fun `mixed control flow must not assume the concrete allocation or null branch`() {
        assertTrue(operand(true).argumentTypes[0] != ClassId("fixture/ConcreteSupplier"))
        assertTrue(operand(true, nullOnly = true).nullArguments.isEmpty())
        assertEquals(setOf(0), operand(false, nullOnly = true).nullArguments)
    }
    @Test
    fun `array elements retain their declared receiver bound`() {
        val bytes = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(V11, ACC_PUBLIC, "fixture/Array", null, "java/lang/Object", null)
            visitMethod(ACC_PUBLIC or ACC_STATIC, "compare", "([Ljava/lang/String;)I", null, null).apply {
                visitCode(); visitVarInsn(ALOAD, 0); visitInsn(ICONST_0); visitInsn(AALOAD)
                visitTypeInsn(CHECKCAST, "java/lang/Comparable"); visitLdcInsn("other")
                visitMethodInsn(INVOKEINTERFACE, "java/lang/Comparable", "compareTo", "(Ljava/lang/Object;)I", true)
                visitInsn(IRETURN); visitMaxs(0, 0); visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val node = ClassNode(); ClassReader(bytes).accept(node, 0)
        val method = node.methods.single()
        val call = CallOperands().analyze(MethodId(ClassId(node.name), method.name, method.desc), method).values.single()
        assertEquals(ClassId("java/lang/String"), call.receiverType)
    }

}
