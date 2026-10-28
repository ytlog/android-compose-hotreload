package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.dex.initializeConstantDelegates
import org.jetbrains.compose.reload.android.host.dex.delegateCompatibleConstructors
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.analysis.ClassInfo
import org.jetbrains.compose.reload.analysis.MutableApplicationInfo
import org.jetbrains.compose.reload.android.host.reload.CacheEvaluation
import org.jetbrains.compose.reload.android.host.reload.ExecutedCall
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.Executors
import javax.tools.ToolProvider
import kotlin.test.*

class DelegateInitializationTest {
    private fun fixture(added: Boolean = true, initial: String = "new-state", dynamic: Boolean = false,
                        seed: Int = 41): ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
        visit(V11, ACC_PUBLIC, "fixture/Owner", null, "java/lang/Object", null)
        visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        visitField(ACC_PUBLIC, "seed", "I", null, null).visitEnd()
        if (added) visitField(ACC_PRIVATE or ACC_FINAL, "added\$delegate", "Landroidx/compose/runtime/MutableState;", null, null).visitEnd()
        visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(ALOAD, 0); visitLdcInsn(seed); visitFieldInsn(PUTFIELD, "fixture/Owner", "seed", "I")
            visitMethodInsn(INVOKESTATIC, "fixture/Owner", "initial", "()Ljava/lang/String;", false); visitInsn(POP)
            if (added) {
                visitVarInsn(ALOAD, 0)
                if (dynamic) visitMethodInsn(INVOKESTATIC, "fixture/Owner", "initial", "()Ljava/lang/String;", false)
                else visitLdcInsn(initial)
                visitInsn(ACONST_NULL); visitInsn(ICONST_2); visitInsn(ACONST_NULL)
                visitMethodInsn(INVOKESTATIC, "androidx/compose/runtime/SnapshotStateKt", "mutableStateOf\$default",
                    "(Ljava/lang/Object;Landroidx/compose/runtime/SnapshotMutationPolicy;ILjava/lang/Object;)Landroidx/compose/runtime/MutableState;", false)
                visitFieldInsn(PUTFIELD, "fixture/Owner", "added\$delegate", "Landroidx/compose/runtime/MutableState;")
            }
            visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
        visitMethod(ACC_PUBLIC or ACC_STATIC, "initial", "()Ljava/lang/String;", null, null).apply {
            visitCode(); visitLdcInsn(initial); visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
        }
        if (added) visitMethod(ACC_PUBLIC, "delegate", "()Landroidx/compose/runtime/MutableState;", null, null).apply {
            visitCode(); visitVarInsn(ALOAD, 0); visitFieldInsn(GETFIELD, "fixture/Owner", "added\$delegate", "Landroidx/compose/runtime/MutableState;")
            visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun withFloat(bytes: ByteArray): ByteArray {
        val node = org.jetbrains.compose.reload.android.host.dex.readClass(bytes)
        val descriptor = "Landroidx/compose/runtime/MutableFloatState;"
        node.fields.add(org.objectweb.asm.tree.FieldNode(ACC_PRIVATE or ACC_FINAL, "float\$delegate", descriptor, null, null))
        val constructor = node.methods.single { it.name == "<init>" }
        constructor.instructions.insertBefore(constructor.instructions.last, org.objectweb.asm.tree.InsnList().apply {
            add(org.objectweb.asm.tree.VarInsnNode(ALOAD, 0)); add(org.objectweb.asm.tree.LdcInsnNode(1.5f))
            add(org.objectweb.asm.tree.MethodInsnNode(INVOKESTATIC, "androidx/compose/runtime/PrimitiveSnapshotStateKt",
                "mutableFloatStateOf", "(F)$descriptor", false))
            add(org.objectweb.asm.tree.FieldInsnNode(PUTFIELD, node.name, "float\$delegate", descriptor))
        })
        node.methods.add(org.objectweb.asm.tree.MethodNode(ACC_PUBLIC, "floatDelegate", "()$descriptor", null, null).apply {
            instructions.add(org.objectweb.asm.tree.VarInsnNode(ALOAD, 0))
            instructions.add(org.objectweb.asm.tree.FieldInsnNode(GETFIELD, node.name, "float\$delegate", descriptor))
            instructions.add(org.objectweb.asm.tree.InsnNode(ARETURN))
        })
        return ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()
    }

    @Test fun `only proven additive stores preserve constructor initialization dependencies`() {
        val id = MethodId(ClassId("fixture/Owner"), "<init>", "()V")
        assertEquals(setOf(id), delegateCompatibleConstructors(fixture(added = false), fixture()))
        assertTrue(delegateCompatibleConstructors(fixture(added = false), fixture(seed = 42)).isEmpty())
        assertTrue(delegateCompatibleConstructors(fixture(added = false), fixture(dynamic = true)).isEmpty())
        assertTrue(delegateCompatibleConstructors(fixture(), fixture(initial = "changed-existing")).isEmpty())
        assertContentEquals(fixture(dynamic = true), initializeConstantDelegates(fixture(dynamic = true)))
    }

    @Test fun `preserving additive construction still traces changes to previously executed helpers`() {
        val old = fixture(added = false)
        val current = MutableApplicationInfo().apply { add(requireNotNull(ClassInfo(old))) }
        val ctor = MethodId(ClassId("fixture/Owner"), "<init>", "()V")
        for (changedHelper in listOf(false, true)) {
            val next = fixture(initial = if (changedHelper) "changed-helper" else "new-state")
            val pending = MutableApplicationInfo().apply { add(requireNotNull(ClassInfo(next))) }
            val compatible = delegateCompatibleConstructors(old, next)
            assertEquals(setOf(ctor), compatible)
            val evaluation = CacheEvaluation(current, pending, pending.methodIndex.keys, emptySet(), compatibleConstructors = compatible)
            assertEquals(changedHelper, evaluation.changed(ExecutedCall(ctor, false)))
        }
    }

    @Test fun `real generated guards initialize null delegates once and retain old object fields`() {
        val root = File(System.getProperty("repository.root"))
        val out = Files.createTempDirectory(root.resolve("build/evidence").apply { mkdirs() }.toPath(), "delegate-host-").toFile()
        val stubs = mapOf(
            "MutableState" to "public interface MutableState { Object getValue(); void setValue(Object value); }",
            "SnapshotMutationPolicy" to "public interface SnapshotMutationPolicy {}",
            "MutableFloatState" to "public interface MutableFloatState { float getFloatValue(); void setFloatValue(float value); }",
            "PrimitiveSnapshotStateKt" to """
                public final class PrimitiveSnapshotStateKt {
                    public static int creations;
                    public static MutableFloatState mutableFloatStateOf(float value) {
                        creations++;
                        return new MutableFloatState() {
                            float current = value;
                            public float getFloatValue() { return current; }
                            public void setFloatValue(float value) { current = value; }
                        };
                    }
                }
            """.trimIndent(),
            "SnapshotStateKt" to """
                public final class SnapshotStateKt {
                    public static int creations;
                    public static SnapshotMutationPolicy structuralEqualityPolicy() { return new SnapshotMutationPolicy() {}; }
                    public static MutableState mutableStateOf${'$'}default(Object value, SnapshotMutationPolicy policy, int mask, Object marker) {
                        return mutableStateOf(value, structuralEqualityPolicy());
                    }
                    public static MutableState mutableStateOf(Object value, SnapshotMutationPolicy policy) {
                        creations++;
                        return new MutableState() {
                            Object current = value;
                            public Object getValue() { return current; }
                            public void setValue(Object value) { current = value; }
                        };
                    }
                }
            """.trimIndent()
        )
        val sources = stubs.map { (name, body) -> out.resolve("$name.java").apply {
            writeText("package androidx.compose.runtime;\n$body")
        } } + root.resolve("modules/runtime/src/main/java/org/jetbrains/compose/reload/android/DelegateInitialization.java")
        val diagnostics = java.io.ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
            "-d", out.path, *sources.map { it.path }.toTypedArray()), diagnostics.toString())
        val withPrimitive = withFloat(fixture())
        assertEquals(setOf(MethodId(ClassId("fixture/Owner"), "<init>", "()V")),
            delegateCompatibleConstructors(fixture(added = false), withPrimitive))
        out.resolve("fixture/Owner.class").apply { parentFile.mkdirs(); writeBytes(initializeConstantDelegates(withPrimitive)) }
        URLClassLoader(arrayOf(out.toURI().toURL()), javaClass.classLoader).use { loader ->
            val ownerClass = loader.loadClass("fixture.Owner")
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val old = (unsafeField.get(null) as sun.misc.Unsafe).allocateInstance(ownerClass)
            ownerClass.getField("seed").setInt(old, 77)
            val reader = ownerClass.getMethod("delegate")
            val pool = Executors.newFixedThreadPool(8)
            val delegates = try { pool.invokeAll((1..32).map { java.util.concurrent.Callable { reader.invoke(old) } }).map { it.get() } }
                finally { pool.shutdownNow() }
            assertTrue(delegates.all { it === delegates.first() })
            assertEquals(77, ownerClass.getField("seed").getInt(old))
            val state = loader.loadClass("androidx.compose.runtime.MutableState")
            assertEquals("new-state", state.getMethod("getValue").invoke(delegates.first()))
            state.getMethod("setValue", Any::class.java).invoke(delegates.first(), "edited")
            assertSame(delegates.first(), reader.invoke(old))
            assertEquals("edited", state.getMethod("getValue").invoke(reader.invoke(old)))
            assertEquals(1, loader.loadClass("androidx.compose.runtime.SnapshotStateKt").getField("creations").getInt(null))
            val floatReader = ownerClass.getMethod("floatDelegate")
            val floatState = loader.loadClass("androidx.compose.runtime.MutableFloatState")
            val originalFloat = floatReader.invoke(old)
            assertEquals(1.5f, floatState.getMethod("getFloatValue").invoke(originalFloat))
            floatState.getMethod("setFloatValue", Float::class.javaPrimitiveType).invoke(originalFloat, 2.25f)
            assertSame(originalFloat, floatReader.invoke(old))
            assertEquals(2.25f, floatState.getMethod("getFloatValue").invoke(floatReader.invoke(old)))
            assertEquals(1, loader.loadClass("androidx.compose.runtime.PrimitiveSnapshotStateKt").getField("creations").getInt(null))
            val fresh = ownerClass.getConstructor().newInstance()
            assertEquals(1.5f, floatState.getMethod("getFloatValue").invoke(floatReader.invoke(fresh)))
            assertEquals(2, loader.loadClass("androidx.compose.runtime.PrimitiveSnapshotStateKt").getField("creations").getInt(null))
            assertNotSame(reader.invoke(old), reader.invoke(fresh))
            assertEquals("new-state", state.getMethod("getValue").invoke(reader.invoke(fresh)))
            assertEquals(2, loader.loadClass("androidx.compose.runtime.SnapshotStateKt").getField("creations").getInt(null))
        }
        // A retained guarded read must still fail at GETFIELD when its field no longer exists.
        val retired = org.jetbrains.compose.reload.android.host.dex.readClass(initializeConstantDelegates(fixture()))
        retired.fields.removeIf { it.name == "added\$delegate" }
        out.resolve("fixture/Owner.class").writeBytes(ClassWriter(ClassWriter.COMPUTE_MAXS).also(retired::accept).toByteArray())
        URLClassLoader(arrayOf(out.toURI().toURL()), javaClass.classLoader).use { loader ->
            val owner = loader.loadClass("fixture.Owner")
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                owner.getMethod("delegate").invoke(unsafe.allocateInstance(owner))
            }
            assertIs<NoSuchFieldError>(failure.cause)
        }
    }
}
