package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.android.host.dex.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.util.function.Supplier
import kotlin.test.*

/** Actual JVM verification/execution of retained closure ABI entries, plus D8 coverage in LambdaIsolationTest. */
class LambdaCompatibilityTest {
    private val id = ClassId("fixture/Callbacks")
    private val supplier = "Ljava/util/function/Supplier;"
    private val metafactory = Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false)

    private data class Body(val name: String, val captures: List<Pair<String, Type>>, val text: String)
    private val string = Type.getType(String::class.java)
    private fun body(name: String = "factory\$lambda\$0", names: List<String> = listOf("value"), text: String = "v1") =
        Body(name, names.map { it to string }, text)

    /** Bodies report each actual capture, so swapping/defaulting captures incorrectly cannot pass. */
    private fun fixture(bodies: List<Body>, debug: Boolean = true): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, id.value, null, "java/lang/Object", null)
        bodies.forEach { body ->
            val descriptor = Type.getMethodDescriptor(string, *body.captures.map { it.second }.toTypedArray())
            val method = writer.visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, body.name, descriptor, null, null)
            method.visitCode()
            val start = Label(); val end = Label()
            method.visitLabel(start)
            method.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            method.visitInsn(Opcodes.DUP)
            method.visitLdcInsn(body.text)
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            var slot = 0
            body.captures.forEach { (_, type) ->
                method.visitLdcInsn(":")
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
                method.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
                val append = when (type.sort) {
                    Type.OBJECT, Type.ARRAY -> "Ljava/lang/Object;"
                    Type.BYTE, Type.SHORT -> "I"
                    else -> type.descriptor
                }
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "($append)Ljava/lang/StringBuilder;", false)
                slot += type.size
            }
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            method.visitInsn(Opcodes.ARETURN)
            method.visitLabel(end)
            if (debug) {
                slot = 0
                body.captures.forEach { (name, type) ->
                    method.visitLocalVariable("$$name", type.descriptor, null, start, end, slot)
                    slot += type.size
                }
            }
            method.visitMaxs(0, 0); method.visitEnd()
        }
        // One ordinary scope, with all source literals in the same factory method.
        val factory = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "factory", "()V", null, null)
        factory.visitCode()
        bodies.forEach { body ->
            body.captures.forEach { (_, type) -> factory.visitInsn(when (type.sort) {
                Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL
                Type.LONG -> Opcodes.LCONST_0
                Type.DOUBLE -> Opcodes.DCONST_0
                Type.FLOAT -> Opcodes.FCONST_0
                else -> Opcodes.ICONST_0
            }) }
            val captureTypes = body.captures.map { it.second }.toTypedArray()
            factory.visitInvokeDynamicInsn("get", Type.getMethodDescriptor(Type.getType(supplier), *captureTypes), metafactory,
                Type.getMethodType("()Ljava/lang/Object;"),
                Handle(Opcodes.H_INVOKESTATIC, id.value, body.name, Type.getMethodDescriptor(string, *captureTypes), false),
                Type.getMethodType("()Ljava/lang/String;"))
            factory.visitInsn(Opcodes.POP)
        }
        factory.visitInsn(Opcodes.RETURN); factory.visitMaxs(0, 0); factory.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun plan(code: ByteArray, previous: LambdaClassState? = null, generation: Int = 1,
                     installed: ByteArray? = null, accessors: List<LambdaAccessor> = emptyList()) =
        prepareLambdas(id, code, code, generation, previous, installed, accessors)

    private fun loader(classes: Map<ClassId, ByteArray>): ClassLoader = object : ClassLoader(javaClass.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[ClassId(name.replace('.', '/'))] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private fun owner(plan: LambdaPlan): Class<*> = loader(plan.classes).loadClass(id.toFqn())

    private fun invoke(plan: LambdaPlan, entry: LambdaEntry, vararg arguments: Any?): String {
        val types = Type.getArgumentTypes(entry.descriptor).map { type -> when (type.sort) {
            Type.INT -> Int::class.javaPrimitiveType!!
            Type.LONG -> Long::class.javaPrimitiveType!!
            Type.DOUBLE -> Double::class.javaPrimitiveType!!
            Type.FLOAT -> Float::class.javaPrimitiveType!!
            Type.BOOLEAN -> Boolean::class.javaPrimitiveType!!
            else -> Class.forName(type.className)
        } }.toTypedArray()
        return owner(plan).getDeclaredMethod(entry.name, *types).apply { isAccessible = true }
            .invoke(null, *arguments) as String
    }

    @Test
    fun `old D8 bridge follows body edits and insertion without ordinal reassignment`() {
        val first = fixture(listOf(body()))
        val legacy = LambdaAccessor("\$r8\$lambda\$installed", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            LambdaAccessor.Target(id.value, "factory\$lambda\$0", "(Ljava/lang/String;)Ljava/lang/String;", LambdaAccessor.Kind.Static))
        val a = plan(first, installed = first, accessors = listOf(legacy))
        val old = a.state.entries.single { it.name == legacy.name }
        val b = plan(fixture(listOf(body(text = "v2"))), a.state, 2)
        assertEquals("v2:retained", invoke(b, old, "retained"))
        // Both inserted and original closures have the same SAM and capture schema; only body identity distinguishes them.
        val c = plan(fixture(listOf(body(text = "wrong"), body("factory\$lambda\$1", text = "v2"))), b.state, 3)
        assertEquals("v2:retained", invoke(c, old, "retained"))
        val d = plan(fixture(listOf(body(text = "wrong"), body("factory\$lambda\$1", text = "v3"))), c.state, 4)
        assertEquals("v3:retained", invoke(d, old, "retained"))
    }

    @Test
    fun `retained factory helper creates an actual closure invoking the newest body`() {
        val a = plan(fixture(listOf(body())))
        val b = plan(fixture(listOf(body(text = "wrong"), body("factory\$lambda\$1"))), a.state, 2)
        val c = plan(fixture(listOf(body(text = "wrong"),
            body("factory\$lambda\$1", listOf("prefix", "value"), "new"))), b.state, 3)
        val oldHelper = a.classes.filterKeys { it != id }
        val loader = loader(c.classes + oldHelper)
        val helper = loader.loadClass(oldHelper.keys.single().toFqn())
        val closure = helper.getMethod("lambda\$0", String::class.java).invoke(null, "kept") as Supplier<*>
        assertEquals("new:null:kept", closure.get())
        val freshHelper = loader.loadClass(c.classes.keys.single { it != id }.toFqn())
        val fresh = freshHelper.declaredMethods.single { it.parameterCount == 2 }
            .invoke(null, "captured", "kept") as Supplier<*>
        assertEquals("new:captured:kept", fresh.get())
    }

    @Test
    fun `added captures default while original captures retain names and values across reorder remove restore`() {
        val a = plan(fixture(listOf(body())))
        val old = a.state.entries.single { it.lambdaId != null }
        val b = plan(fixture(listOf(body(names = listOf("prefix", "value"), text = "new"))), a.state, 2)
        assertEquals("new:null:kept", invoke(b, old, "kept"))
        val fresh = b.state.entries.last { it.lambdaId != null }
        assertEquals("new:captured:kept", invoke(b, fresh, "captured", "kept"))
        val c = plan(fixture(listOf(body(names = listOf("value", "prefix"), text = "reordered"))), b.state, 3)
        assertEquals("reordered:kept:null", invoke(c, old, "kept"))
        assertEquals("reordered:kept:captured", invoke(c, fresh, "captured", "kept"))
        val d = plan(fixture(listOf(body(names = listOf("value"), text = "removed"))), c.state, 4)
        assertEquals("removed:kept", invoke(d, fresh, "captured", "kept"))
        val e = plan(fixture(listOf(body(names = listOf("prefix", "value"), text = "restored"))), d.state, 5)
        assertEquals("restored:null:kept", invoke(e, old, "kept"))
        assertEquals("restored:captured:kept", invoke(e, fresh, "captured", "kept"))
    }

    @Test
    fun `primitive and wide new captures use typed defaults and retained slots`() {
        val long = Type.LONG_TYPE; val double = Type.DOUBLE_TYPE; val int = Type.INT_TYPE
        val first = Body("factory\$lambda\$0", listOf("wide" to long, "value" to string, "real" to double), "old")
        val a = plan(fixture(listOf(first)))
        val old = a.state.entries.single { it.lambdaId != null }
        val changed = first.copy(captures = listOf("new" to int, "flag" to Type.BOOLEAN_TYPE, "fraction" to Type.FLOAT_TYPE, "real" to double, "wide" to long, "value" to string), text = "new")
        val b = plan(fixture(listOf(changed)), a.state, 2)
        assertEquals("new:0:false:0.0:3.5:42:kept", invoke(b, old, 42L, "kept", 3.5))
    }

    private fun sourceLocations(code: ByteArray, offset: Int, owner: String = "androidx/compose/runtime/ComposerKt"): ByteArray {
        val node = readClass(code)
        node.methods.filter { it.name.contains("\$lambda\$") }.forEachIndexed { index, method ->
            method.instructions.insert(InsnList().apply {
                add(InsnNode(Opcodes.ACONST_NULL))
                add(LdcInsnNode("C0@${offset + index}L1:Screen.kt#fixture"))
                add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, "sourceInformation",
                    "(Landroidx/compose/runtime/Composer;Ljava/lang/String;)V", false))
            })
        }
        return ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()
    }

    private fun sourceInformationStubs(): Map<ClassId, ByteArray> {
        val composer = ClassWriter(0).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                "androidx/compose/runtime/Composer", null, "java/lang/Object", null); visitEnd()
        }.toByteArray()
        val intrinsics = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "androidx/compose/runtime/ComposerKt", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sourceInformation",
                "(Landroidx/compose/runtime/Composer;Ljava/lang/String;)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }
            visitEnd()
        }.toByteArray()
        return mapOf(ClassId("androidx/compose/runtime/Composer") to composer,
            ClassId("androidx/compose/runtime/ComposerKt") to intrinsics)
    }

    @Test
    fun `shifted Compose source offsets preserve multiple retained closures and emitted debug information`() {
        val first = listOf(body(text = "first"), body("factory\$lambda\$1", text = "second"))
        val a = plan(sourceLocations(fixture(first), 10))
        val entries = a.state.entries.filter { it.lambdaId != null }
        val b = plan(sourceLocations(fixture(first), 100), a.state, 2)
        val c = plan(sourceLocations(fixture(listOf(body(text = "updated"), first.last())), 200), b.state, 3)
        val executable = c.copy(classes = c.classes + sourceInformationStubs())
        assertEquals("updated:kept", invoke(executable, entries[0], "kept"))
        assertEquals("second:kept", invoke(executable, entries[1], "kept"))
        val literals = readClass(c.classes.getValue(id)).methods.flatMap { it.instructions.filterIsInstance<LdcInsnNode>() }.map { it.cst }
        assertTrue("C0@200L1:Screen.kt#fixture" in literals, "Only the fingerprint may normalize source information")
    }

    @Test
    fun `source shaped business strings and lookalike intrinsics do not relax ambiguity rejection`() {
        val a = plan(fixture(listOf(body(text = "C0@10L1:Screen.kt"), body("factory\$lambda\$1", text = "C0@20L1:Screen.kt"))))
        assertFailsWith<IllegalStateException> {
            plan(fixture(listOf(body(text = "C0@11L1:Screen.kt"), body("factory\$lambda\$1", text = "C0@21L1:Screen.kt"))), a.state, 2)
        }
        val bodies = listOf(body(text = "first"), body("factory\$lambda\$1", text = "second"))
        val lookalike = plan(sourceLocations(fixture(bodies), 10, "fixture/ComposerKt"))
        assertFailsWith<IllegalStateException> {
            plan(sourceLocations(fixture(bodies), 20, "fixture/ComposerKt"), lookalike.state, 2)
        }
    }

    @Test
    fun `ambiguous edited insertion is rejected and discarded attempt cannot alter active entries`() {
        val a = plan(fixture(listOf(body())))
        val old = a.state.entries.single { it.lambdaId != null }
        assertFailsWith<IllegalStateException> {
            plan(fixture(listOf(body(text = "other"), body("factory\$lambda\$1", text = "changed"))), a.state, 2)
        }.also { assertTrue(it.message!!.contains("Ambiguous lambda identity")) }
        val discarded = plan(fixture(listOf(body(names = listOf("extra", "value"), text = "not applied"))), a.state, 3)
        assertEquals(1, a.state.versions.size)
        assertEquals("v1:kept", invoke(a, old, "kept"))
        val retry = plan(fixture(listOf(body(text = "retry"))), a.state, 4)
        assertEquals("retry:kept", invoke(retry, old, "kept"))
        assertTrue(discarded.state.entries.size > retry.state.entries.size)
    }

    @Test
    fun `inserting the old capture schema while evolving another body refuses reassignment`() {
        val a = plan(fixture(listOf(body())))
        assertFailsWith<IllegalStateException> {
            plan(fixture(listOf(body(text = "decoy"),
                body("factory\$lambda\$1", listOf("new", "value"), "changed"))), a.state, 2)
        }.also { assertTrue(it.message!!.contains("Ambiguous lambda identity")) }
    }

    @Test
    fun `initial direct compiler entry survives descriptor and ordinal changes without a D8 accessor`() {
        val first = fixture(listOf(body()))
        val a = plan(first, installed = first)
        val direct = a.state.entries.single { it.name == "factory\$lambda\$0" }
        val b = plan(fixture(listOf(body(names = listOf("prefix", "value"), text = "new"))), a.state, 2)
        assertEquals("new:null:kept", invoke(b, direct, "kept"))
        // A statics-only rewrite may have been skipped on the device: re-seeding must recreate the same ABI.
        val retry = plan(fixture(listOf(body(names = listOf("prefix", "value"), text = "retry"))), installed = first)
        assertEquals("retry:null:kept", invoke(retry, direct, "kept"))
        assertEquals(a.state.entries.filter { it.name.startsWith(LAMBDA_PREFIX) },
            plan(first, installed = first).state.entries.filter { it.name.startsWith(LAMBDA_PREFIX) })
    }

    @Test
    fun `initial instance compiler entry preserves its implicit receiver and old capture slots`() {
        fun instance(code: ByteArray): ByteArray {
            val node = readClass(code)
            node.methods.filter { it.name.contains("\$lambda\$") }.forEach { method ->
                method.access = Opcodes.ACC_PUBLIC
                method.instructions.filterIsInstance<VarInsnNode>().forEach { it.`var`++ }
                method.localVariables.forEach { it.index++ }
            }
            node.lambdaSites().forEach { (method, site) ->
                val handle = site.implementation()
                method.instructions.insertBefore(site, InsnNode(Opcodes.ACONST_NULL))
                site.desc = Type.getMethodDescriptor(Type.getReturnType(site.desc),
                    Type.getObjectType(id.value), *Type.getArgumentTypes(site.desc))
                site.bsmArgs[1] = Handle(Opcodes.H_INVOKEVIRTUAL, handle.owner, handle.name, handle.desc, false)
            }
            node.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                instructions.add(InsnNode(Opcodes.RETURN))
            })
            return ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()
        }
        val first = instance(fixture(listOf(body())))
        val a = plan(first, installed = first)
        val direct = a.state.entries.single { it.name == "factory\$lambda\$0" }
        assertTrue(direct.instance)
        val b = plan(instance(fixture(listOf(body(names = listOf("prefix", "value"), text = "new")))), a.state, 2)
        val owner = owner(b)
        assertEquals("new:null:kept", owner.getMethod(direct.name, String::class.java)
            .invoke(owner.getConstructor().newInstance(), "kept"))
    }

    @Test
    fun `missing capture debug metadata fails before unsafe binding adaptation`() {
        val code = fixture(listOf(body()), debug = false)
        val a = plan(code)
        assertFailsWith<IllegalStateException> {
            plan(fixture(listOf(body(names = listOf("new", "value"))), debug = false), a.state, 2)
        }.also { assertTrue(it.message!!.contains("Capture debug names required")) }
    }

    @Test
    fun `removed source lambda retains its last callable body`() {
        val a = plan(fixture(listOf(body())))
        val old = a.state.entries.single { it.lambdaId != null }
        val b = plan(fixture(emptyList()), a.state, 2)
        assertFalse(b.state.versions.single().active)
        assertEquals("v1:kept", invoke(b, old, "kept"))
    }
}
