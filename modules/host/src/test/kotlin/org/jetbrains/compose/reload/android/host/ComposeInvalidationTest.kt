package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.analysis.ClassInfo
import org.jetbrains.compose.reload.analysis.MutableApplicationInfo
import org.jetbrains.compose.reload.android.host.reload.composeInvalidation
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import org.objectweb.asm.Opcodes.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ComposeInvalidationTest {
    private fun app(text: String = "before", childKey: Int = 200, includeGroup: Boolean = true,
                    cache: Boolean = false, helper: String = "old", lambda: Boolean = false,
                    cacheField: Boolean = false, initial: String = "old field",
                    outside: Boolean = false, debug: Boolean = true, keyComparison: Int = 0) =
        MutableApplicationInfo().apply {
            add(requireNotNull(ClassInfo(ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
                visit(V11, ACC_PUBLIC, "fixture/Screen", null, "java/lang/Object", null)
                visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "initial", "Ljava/lang/String;", null, initial).visitEnd()
                visitMethod(ACC_PUBLIC or ACC_STATIC, "Screen", "(Landroidx/compose/runtime/Composer;)V", null, null).apply {
                    visitCode()
                    fun start(key: Int) {
                        visitVarInsn(ALOAD, 0)
                        visitLdcInsn(key)
                        visitMethodInsn(INVOKEINTERFACE, "androidx/compose/runtime/Composer", "startRestartGroup",
                            "(I)Landroidx/compose/runtime/Composer;", true)
                        visitVarInsn(ASTORE, 0)
                    }
                    fun end() {
                        visitVarInsn(ALOAD, 0)
                        visitMethodInsn(INVOKEINTERFACE, "androidx/compose/runtime/Composer", "endRestartGroup",
                            "()Landroidx/compose/runtime/ScopeUpdateScope;", true)
                        visitInsn(POP)
                    }
                    if (includeGroup) start(100)
                    if (outside) {
                        visitMethodInsn(INVOKESTATIC, "fixture/Screen", "helper", "()Ljava/lang/String;", false)
                        visitInsn(POP)
                    }
                    if (cache) {
                        if (keyComparison != 0) {
                            val bypass = Label()
                            if (keyComparison == 1) { visitInsn(ICONST_0); visitJumpInsn(IFEQ, bypass) }
                            visitVarInsn(ALOAD, 0); visitInsn(ACONST_NULL)
                            visitMethodInsn(INVOKEINTERFACE, "androidx/compose/runtime/Composer", "changedInstance", "(Ljava/lang/Object;)Z", true)
                            visitInsn(POP); visitLabel(bypass)
                        }
                        val cacheStart = Label()
                        val calculationStart = Label()
                        val calculationEnd = Label()
                        val cacheEnd = Label()
                        visitLabel(cacheStart)
                        visitVarInsn(ALOAD, 0)
                        visitMethodInsn(INVOKEINTERFACE, "androidx/compose/runtime/Composer", "rememberedValue", "()Ljava/lang/Object;", true)
                        visitInsn(POP)
                        visitLabel(calculationStart)
                        if (cacheField) {
                            visitFieldInsn(GETSTATIC, "fixture/Screen", "initial", "Ljava/lang/String;")
                        } else if (lambda) {
                            visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;",
                                Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                                Type.getMethodType("()Ljava/lang/Object;"),
                                Handle(H_INVOKESTATIC, "fixture/Screen", "helper", "()Ljava/lang/String;", false),
                                Type.getMethodType("()Ljava/lang/String;"))
                        } else if (outside) {
                            visitLdcInsn("unrelated cached value")
                        } else {
                            visitMethodInsn(INVOKESTATIC, "fixture/Screen", "helper", "()Ljava/lang/String;", false)
                        }
                        visitInsn(POP)
                        visitLabel(calculationEnd)
                        visitLabel(cacheEnd)
                        if (debug) {
                            visitLocalVariable("\$i\$f\$cache", "I", null, cacheStart, cacheEnd, 1)
                            visitLocalVariable("\$i\$a\$-cache-test", "I", null, calculationStart, calculationEnd, 2)
                        }
                    }
                    visitLdcInsn(text)
                    visitInsn(POP)
                    start(childKey)
                    visitLdcInsn("child")
                    visitInsn(POP)
                    end()
                    if (includeGroup) end()
                    visitInsn(RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
                visitMethod(ACC_PUBLIC or ACC_STATIC, "helper", "()Ljava/lang/String;", null, null).apply {
                    visitCode(); visitLdcInsn(helper); visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
                }
                visitEnd()
            }.toByteArray())))
        }

    @Test
    fun `unchanged caller of dirty dependency recomposes without resetting its keyed children`() {
        val before = app()
        assertTrue(before.groupIndex.keys.any { it?.key == 100 })
        val dirty = before.groupIndex.values.flatten()
        val plan = composeInvalidation(before, MutableApplicationInfo(), dirty)
        assertContentEquals(intArrayOf(), plan.reset)
        assertContentEquals(intArrayOf(100, 200).sortedArray(), plan.recompose.sortedArray())
    }

    @Test
    fun `constant edit in stateless rendering recomposes without bashing dormant lazy nodes`() {
        val before = app()
        val after = app(text = "after")
        val plan = composeInvalidation(before, after, after.groupIndex.values.flatten())
        assertContentEquals(intArrayOf(), plan.reset)
        assertContentEquals(intArrayOf(100, 200).sortedArray(), plan.recompose.sortedArray())
    }

    @Test
    fun `nested slot structure changes also reset the parent`() {
        val before = app()
        val after = app(childKey = 201)
        val plan = composeInvalidation(before, after, before.groupIndex.values.flatten() + after.groupIndex.values.flatten())
        assertContentEquals(intArrayOf(100, 200, 201).sortedArray(), plan.reset.sortedArray())
        assertContentEquals(intArrayOf(), plan.recompose)
    }

    @Test
    fun `editing a remembered initializer still resets that scope`() {
        val before = app(cache = true)
        val after = app(cache = true, text = "changed initializer")
        val plan = composeInvalidation(before, after, after.groupIndex.values.flatten())
        assertContentEquals(intArrayOf(100), plan.reset)
    }

    @Test
    fun `dirty dependency evaluated by remember still resets the unchanged caller`() {
        val before = app(cache = true)
        val after = app(cache = true, helper = "new")
        val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.single { it.methodId.methodName == "helper" }.rootScope
        val plan = composeInvalidation(before, after, dirty)
        assertContentEquals(intArrayOf(100), plan.reset)
    }

    @Test
    fun `dirty body of a cached closure does not invalidate the closure initializer`() {
        val before = app(cache = true, lambda = true)
        val after = app(cache = true, lambda = true, helper = "new")
        val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.single { it.methodId.methodName == "helper" }.rootScope
        val plan = composeInvalidation(before, after, dirty)
        assertContentEquals(intArrayOf(), plan.reset)
        assertContentEquals(intArrayOf(100, 200).sortedArray(), plan.recompose.sortedArray())
    }

    @Test
    fun `changed field evaluated by remember resets the unchanged caller`() {
        val before = app(cache = true, cacheField = true)
        val after = app(cache = true, cacheField = true, initial = "new field")
        val plan = composeInvalidation(before, after, after.groupIndex.values.flatten())
        assertContentEquals(intArrayOf(100), plan.reset)
    }

    @Test
    fun `dirty list computation outside a cache does not reset unrelated cached state`() {
        val before = app(cache = true, outside = true)
        val after = app(cache = true, outside = true, helper = "new list")
        val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.single { it.methodId.methodName == "helper" }.rootScope
        val plan = composeInvalidation(before, after, dirty)
        assertContentEquals(intArrayOf(), plan.reset)
    }

    @Test
    fun `missing cache ranges conservatively retain group reset`() {
        val before = app(cache = true, debug = false)
        val plan = composeInvalidation(before, MutableApplicationInfo(), before.groupIndex.values.flatten())
        assertContentEquals(intArrayOf(100), plan.reset)
    }

    @Test
    fun `conditional versus unconditional intrinsic comparison changes real cache slot layout`() {
        val before = app(cache = true, keyComparison = 1)
        val after = app(cache = true, keyComparison = 2)
        // Identical call/group lists are insufficient: the comparison may consume a different slot.
        val plan = composeInvalidation(before, after, after.groupIndex.values.flatten())
        assertContentEquals(intArrayOf(100), plan.reset)
    }

    @Test
    fun `removed groups are never classified as unchanged`() {
        val before = app()
        val after = app(includeGroup = false)
        val plan = composeInvalidation(before, after, before.groupIndex.values.flatten())
        assertTrue(100 in plan.reset)
        assertTrue(100 !in plan.recompose)
    }
}
