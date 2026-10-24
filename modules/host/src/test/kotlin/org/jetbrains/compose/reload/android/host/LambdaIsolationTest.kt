package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.dex.Dexer
import org.jetbrains.compose.reload.android.host.dex.prepareLambdas
import org.jetbrains.compose.reload.android.host.dex.LambdaAccessor
import org.jetbrains.compose.reload.android.host.dex.readLambdaAccessors

import org.jetbrains.compose.reload.analysis.ClassId
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlin compiles these lambdas with 'invokedynamic' to private implementation methods */
@Suppress("unused")
class LambdaFixture(private val offset: Int) {
    fun adder(): (Int) -> Int = { it + offset }
    fun greeter(): () -> String = { "Hello" }
    fun runnable(): Runnable = Runnable { println(offset) }
}

/** With minSdk 30 D8 keeps these default methods and their private static lambda targets on the interface. */
@Suppress("unused")
interface InterfaceLambdaFixture {
    fun adder(offset: Int): (Int) -> Int = { it + offset }
    fun greeter(): () -> String = { "Hello" }
    fun runnable(): Runnable = Runnable { println("interface") }
}

class LambdaIsolationTest {
    private val androidJar: File by lazy {
        val sdk = System.getenv("ANDROID_HOME")?.let(::File) ?: run {
            val properties = Properties()
            File(System.getProperty("repository.root"), "local.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
            properties.getProperty("sdk.dir")?.let(::File)
        } ?: error("Android SDK not found: set ANDROID_HOME or 'sdk.dir' in local.properties")
        sdk.resolve("platforms").listFiles().orEmpty().filter { it.resolve("android.jar").isFile }
            .maxBy { it.name }.resolve("android.jar")
    }

    private val classId = ClassId(LambdaFixture::class.java.name.replace('.', '/'))
    private val bytecode = LambdaFixture::class.java.getResourceAsStream("LambdaFixture.class")!!.readBytes()
    private val dexer by lazy { Dexer(30, listOf(androidJar), emptyList()) }

    private fun isolateLambdas(id: ClassId, code: ByteArray, generation: Int, accessors: List<LambdaAccessor>) =
        prepareLambdas(id, code, code, generation, null, code, accessors).classes

    @Test
    fun `accessors of the original class are kept`() {
        val original = dexer.dex(mapOf(classId to bytecode))
        val accessors = readLambdaAccessors(original.getValue(classId.toFqn()), classId.value)
        assertEquals(3, accessors.size, "Expected one D8 accessor per private lambda implementation: $accessors")
        assertTrue(accessors.all { it.target.owner == classId.value })

        val isolated = dexer.dex(isolateLambdas(classId, bytecode, 7, accessors))
        val isolatedAccessors = readLambdaAccessors(isolated.getValue(classId.toFqn()), classId.value)
        assertEquals(accessors.map { it.name + it.descriptor }.toSet(), isolatedAccessors.map { it.name + it.descriptor }.toSet())
    }

    @Test
    fun `synthetic lambda classes belong to the helper`() {
        val original = dexer.dex(mapOf(classId to bytecode))
        assertTrue(original.keys.any { it.startsWith(classId.toFqn() + "$$") }, "D8 desugars the lambdas: ${original.keys}")

        val accessors = readLambdaAccessors(original.getValue(classId.toFqn()), classId.value)
        val isolated = dexer.dex(isolateLambdas(classId, bytecode, 7, accessors))
        val helper = classId.toFqn() + "\$chr\$lambdas\$7"
        val synthetics = isolated.keys - classId.toFqn() - helper
        assertEquals(3, synthetics.size, "Expected one synthetic class per lambda: ${isolated.keys}")
        assertTrue(synthetics.all { it.startsWith("$helper$$") }, "Synthetic classes must not be redefined: $synthetics")
    }

    @Test
    fun `interface lambdas move to the helper and retain their original bridges`() {
        val id = ClassId(InterfaceLambdaFixture::class.java.name.replace('.', '/'))
        val code = InterfaceLambdaFixture::class.java.getResourceAsStream("InterfaceLambdaFixture.class")!!.readBytes()
        val original = dexer.dex(mapOf(id to code))
        val accessors = readLambdaAccessors(original.getValue(id.toFqn()), id.value)
        assertEquals(3, accessors.size)

        val classes = isolateLambdas(id, code, 9, accessors)
        val node = ClassNode().also { ClassReader(classes.getValue(id)).accept(it, 0) }
        val bridges = node.methods.filter { method -> accessors.any { it.name == method.name } }
        assertEquals(3, bridges.size)
        bridges.forEach { bridge ->
            assertEquals(0, bridge.access and (Opcodes.ACC_FINAL or Opcodes.ACC_PROTECTED))
            assertTrue(bridge.access and Opcodes.ACC_PUBLIC != 0)
            assertTrue(bridge.instructions.filterIsInstance<MethodInsnNode>().single().itf,
                "Static interface targets require InterfaceMethodref too")
        }

        val isolated = dexer.dex(classes)
        val helper = id.toFqn() + "\$chr\$lambdas\$9"
        val synthetics = isolated.keys - id.toFqn() - helper
        assertEquals(3, synthetics.size)
        assertTrue(synthetics.all { it.startsWith("$helper$$") })
        val isolatedAccessors = readLambdaAccessors(isolated.getValue(id.toFqn()), id.value)
        assertEquals(accessors.map { it.name + it.descriptor }.toSet(),
            isolatedAccessors.map { it.name + it.descriptor }.toSet())
    }

    @Test
    fun `installed compiler entry modifiers are preserved on classes and interfaces`() {
        for (fixture in listOf(LambdaFixture::class.java, InterfaceLambdaFixture::class.java)) {
            val id = ClassId(fixture.name.replace('.', '/'))
            val code = fixture.getResourceAsStream("${fixture.simpleName}.class")!!.readBytes()
            val original = ClassNode().also { ClassReader(code).accept(it, 0) }
            val accessors = readLambdaAccessors(dexer.dex(mapOf(id to code)).getValue(id.toFqn()), id.value)
            val rewritten = isolateLambdas(id, code, 11, accessors)
            val node = ClassNode().also { ClassReader(rewritten.getValue(id)).accept(it, 0) }
            original.methods.filter { it.name.contains("\$lambda\$") }.forEach { before ->
                val retained = node.methods.single { it.name == before.name && it.desc == before.desc }
                assertEquals(before.access, retained.access, "Retained compiler entry modifiers: ${fixture.simpleName}.${before.name}")
            }
        }
    }

    @Test
    fun `classes without lambdas are unchanged`() {
        val plain = ClassId("org/jetbrains/compose/reload/android/host/HostConfig")
        val plainBytecode = HostConfig::class.java.getResourceAsStream("HostConfig.class")!!.readBytes()
        val result = isolateLambdas(plain, plainBytecode, 1, emptyList())
        assertEquals(setOf(plain), result.keys)
        assertTrue(result.getValue(plain).contentEquals(plainBytecode))
    }
}
