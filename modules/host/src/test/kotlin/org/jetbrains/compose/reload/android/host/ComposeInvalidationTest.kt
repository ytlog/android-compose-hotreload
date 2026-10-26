package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.analysis.ClassInfo
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.android.host.reload.DeferredLibraryCalls
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
                    outside: Boolean = false, debug: Boolean = true, keyComparison: Int = 0,
                    factory: Boolean = false, invoke: Boolean = false, eager: Boolean = false,
                    classLambda: Boolean = false, library: Boolean = false, initializer: String? = null,
                    lazyApi: MethodId? = null, readWrapper: Boolean = false,
                    callbackType: String = "java/util/function/Supplier", callbackMethod: String = "get",
                    callbackArgs: String = "") =
        MutableApplicationInfo().apply {
            add(requireNotNull(ClassInfo(ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
                visit(V11, ACC_PUBLIC, "fixture/Screen", null, "java/lang/Object", null)
                visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, "initial", "Ljava/lang/String;", null, initial).visitEnd()
                if (initializer != null) visitMethod(ACC_STATIC, "<clinit>", "()V", null, null).apply {
                    visitCode(); visitLdcInsn(initializer)
                    visitFieldInsn(PUTSTATIC, "fixture/Screen", "initial", "Ljava/lang/String;")
                    visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
                }
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
                            visitInvokeDynamicInsn(callbackMethod, "()L$callbackType;",
                                Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                                Type.getMethodType("($callbackArgs)Ljava/lang/Object;"),
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
                    visitCode()
                    if (factory) {
                        visitMethodInsn(INVOKESTATIC, "fixture/Screen", "factory", "()L$callbackType;", false)
                        if (lazyApi != null) {
                            if (Type.getArgumentTypes(lazyApi.methodDescriptor).size == 2) {
                                visitInsn(ACONST_NULL); visitInsn(SWAP)
                            }
                            visitMethodInsn(INVOKESTATIC, lazyApi.classId.value, lazyApi.methodName, lazyApi.methodDescriptor, false)
                            if (readWrapper) {
                                val owner = Type.getReturnType(lazyApi.methodDescriptor).internalName
                                if (owner == "kotlinx/coroutines/flow/Flow") {
                                    visitInsn(ACONST_NULL); visitInsn(ACONST_NULL)
                                    visitMethodInsn(INVOKEINTERFACE, owner, "collect", "(Lkotlinx/coroutines/flow/FlowCollector;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", true)
                                } else visitMethodInsn(INVOKEINTERFACE, owner, "getValue", "()Ljava/lang/Object;", true)
                                visitTypeInsn(CHECKCAST, "java/lang/String")
                            } else { visitInsn(POP); visitLdcInsn("deferred") }
                        } else if (library) {
                            visitMethodInsn(INVOKESTATIC, "library/Consumer", "consume", "(Ljava/util/function/Supplier;)Ljava/lang/String;", false)
                        } else if (invoke) {
                            visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true)
                            visitTypeInsn(CHECKCAST, "java/lang/String")
                        } else { visitInsn(POP); visitLdcInsn("holder") }
                    } else visitLdcInsn(helper)
                    visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
                }
                if (factory) {
                    visitMethod(ACC_PUBLIC or ACC_STATIC, "callback", "($callbackArgs)Ljava/lang/String;", null, null).apply {
                        visitCode(); visitLdcInsn(helper); visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
                    }
                    visitMethod(ACC_PUBLIC or ACC_STATIC, "factory", "()L$callbackType;", null, null).apply {
                        visitCode()
                        if (eager) {
                            visitMethodInsn(INVOKESTATIC, "fixture/Screen", "callback", "($callbackArgs)Ljava/lang/String;", false)
                            visitInsn(POP)
                        }
                        if (classLambda) {
                            visitTypeInsn(NEW, "fixture/Listener"); visitInsn(DUP)
                            visitMethodInsn(INVOKESPECIAL, "fixture/Listener", "<init>", "()V", false)
                        } else visitInvokeDynamicInsn(callbackMethod, "()L$callbackType;",
                            Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                            Type.getMethodType("($callbackArgs)Ljava/lang/Object;"),
                            Handle(H_INVOKESTATIC, "fixture/Screen", "callback", "($callbackArgs)Ljava/lang/String;", false),
                            Type.getMethodType("($callbackArgs)Ljava/lang/String;"))
                        visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
                    }
                }
                visitEnd()
            }.toByteArray())))
            if (classLambda) add(requireNotNull(ClassInfo(ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
                visit(V11, ACC_PUBLIC, "fixture/Listener", null, "java/lang/Object", arrayOf(callbackType))
                visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
                    visitCode(); visitVarInsn(ALOAD, 0)
                    visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                    visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
                }
                visitMethod(ACC_PUBLIC, callbackMethod, "($callbackArgs)Ljava/lang/Object;", null, null).apply {
                    visitCode(); visitLdcInsn(helper); visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
                }
                visitEnd()
            }.toByteArray())))
        }

    private data class LazyApi(val method: MethodId, val arity: Int)
    private val lazyApis = listOf(
        LazyApi(MethodId(ClassId("androidx/compose/runtime/SnapshotStateKt"), "derivedStateOf",
            "(Lkotlin/jvm/functions/Function0;)Landroidx/compose/runtime/State;"), 0),
        LazyApi(MethodId(ClassId("androidx/compose/runtime/SnapshotStateKt"), "derivedStateOf",
            "(Landroidx/compose/runtime/SnapshotMutationPolicy;Lkotlin/jvm/functions/Function0;)Landroidx/compose/runtime/State;"), 0),
        LazyApi(MethodId(ClassId("androidx/compose/runtime/SnapshotStateKt"), "snapshotFlow",
            "(Lkotlin/jvm/functions/Function0;)Lkotlinx/coroutines/flow/Flow;"), 0),
        LazyApi(MethodId(ClassId("kotlinx/coroutines/flow/FlowKt"), "flow",
            "(Lkotlin/jvm/functions/Function2;)Lkotlinx/coroutines/flow/Flow;"), 2),
        *listOf("onStart", "onEach", "onCompletion").map { name ->
            val arity = if (name == "onCompletion") 3 else 2
            LazyApi(MethodId(ClassId("kotlinx/coroutines/flow/FlowKt"), name,
                "(Lkotlinx/coroutines/flow/Flow;Lkotlin/jvm/functions/Function$arity;)Lkotlinx/coroutines/flow/Flow;"), arity)
        }.toTypedArray(),
    )

    @Test
    fun `lazy library factories retain state for deferred body edits but consumers still reset`() {
        for ((api, arity) in lazyApis) for (read in listOf(false, true)) for (classLambda in listOf(false, true)) {
            fun fixture(value: String) = app(cache = true, factory = true, lazyApi = api, readWrapper = read,
                classLambda = classLambda, callbackType = "kotlin/jvm/functions/Function$arity", callbackMethod = "invoke",
                callbackArgs = "Ljava/lang/Object;".repeat(arity), helper = value)
            val before = fixture("before")
            val after = fixture("after")
            val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.map { it.rootScope }
            assertContentEquals(if (read) intArrayOf(100) else intArrayOf(),
                composeInvalidation(before, after, dirty).reset, "$api read=$read classLambda=$classLambda")
        }
    }

    @Test
    fun `lazy contracts do not cover lookalike owners overloads or terminal operations`() {
        val known = lazyApis.first().method
        assertTrue(DeferredLibraryCalls.defers(known))
        assertTrue(!DeferredLibraryCalls.defers(MethodId(ClassId("fixture/SnapshotStateKt"), known.methodName, known.methodDescriptor)))
        assertTrue(!DeferredLibraryCalls.defers(MethodId(known.classId, known.methodName,
            "(Lkotlin/jvm/functions/Function0;I)Landroidx/compose/runtime/State;")))
        assertTrue(!DeferredLibraryCalls.defers(MethodId(ClassId("kotlinx/coroutines/flow/FlowKt"), "launchIn",
            "(Lkotlinx/coroutines/flow/Flow;Lkotlinx/coroutines/CoroutineScope;)Lkotlinx/coroutines/Job;")))
        val consumer = MethodId(ClassId("library/Consumer"), "consume", "(Landroidx/compose/runtime/State;Lkotlinx/coroutines/flow/Flow;)V")
        val types = DeferredLibraryCalls.evaluatedTypes(consumer)
        assertTrue(ClassId("kotlin/jvm/functions/Function0") in types)
        assertTrue(ClassId("kotlin/jvm/functions/Function3") in types)
    }

    @Test
    fun `opaque library accepting a callback must conservatively reset`() {
        for (classLambda in listOf(false, true)) {
            val before = app(cache = true, factory = true, library = true, classLambda = classLambda)
            val after = app(cache = true, factory = true, library = true, classLambda = classLambda, helper = "new callback")
            val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.map { it.rootScope }
            assertContentEquals(intArrayOf(100), composeInvalidation(before, after, dirty).reset)
        }
    }

    @Test
    fun `static initializer read directly or through a helper must reset`() {
        for (field in listOf(false, true)) {
            val before = app(cache = true, cacheField = field, initializer = "old value")
            val after = app(cache = true, cacheField = field, initializer = "new value")
            val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.map { it.rootScope }
            assertContentEquals(intArrayOf(100), composeInvalidation(before, after, dirty).reset)
        }
    }

    @Test
    fun `transitively dirty callback factory is not evaluated callback execution`() {
        for (classLambda in listOf(false, true)) {
            val before = app(cache = true, factory = true, classLambda = classLambda)
            val after = app(cache = true, factory = true, classLambda = classLambda, helper = "new callback")
            val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.map { it.rootScope }
            assertContentEquals(intArrayOf(), composeInvalidation(before, after, dirty).reset)
        }
    }

    @Test
    fun `callback actually invoked during cached initialization must reset`() {
        for (classLambda in listOf(false, true)) {
            val before = app(cache = true, factory = true, invoke = true, classLambda = classLambda)
            val after = app(cache = true, factory = true, invoke = true, classLambda = classLambda, helper = "new callback")
            val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.map { it.rootScope }
            assertContentEquals(intArrayOf(100), composeInvalidation(before, after, dirty).reset)
        }
    }

    @Test
    fun `factory computing a value before returning a callback must reset`() {
        val before = app(cache = true, factory = true, eager = true)
        val after = app(cache = true, factory = true, eager = true, helper = "new evaluated value")
        val dirty = after.groupIndex.values.flatten() + after.methodIndex.values.map { it.rootScope }
        assertContentEquals(intArrayOf(100), composeInvalidation(before, after, dirty).reset)
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
