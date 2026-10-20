package org.jetbrains.compose.reload.android.host.dex

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An accessor D8 creates when desugaring a lambda whose implementation method is not accessible
 * from the synthetic lambda class (e.g. private): `static $r8$lambda$<hash>(...)` forwarding to the implementation.
 */
internal data class LambdaAccessor(
    val name: String,
    val descriptor: String,
    val access: Int,
    val target: Target,
) {
    data class Target(val owner: String, val name: String, val descriptor: String, val kind: Kind)
    enum class Kind { Static, Direct, Virtual, Interface }
}

/** Reads the '$r8$lambda$' accessors of [internalName] from a DEX file produced by D8 */
internal fun readLambdaAccessors(dex: ByteArray, internalName: String): List<LambdaAccessor> {
    val reader = DexReader(dex)
    val classDescriptor = "L$internalName;"
    return reader.classDefinitions().filter { it.descriptor == classDescriptor }.flatMap { definition ->
        definition.methods.mapNotNull { method ->
            val reference = reader.method(method.methodIndex)
            if (!reference.name.startsWith(ACCESSOR_PREFIX)) return@mapNotNull null
            val target = reader.firstInvoke(method.codeOffset) ?: return@mapNotNull null
            LambdaAccessor(reference.name, reference.descriptor, method.accessFlags, target)
        }
    }
}

private const val ACCESSOR_PREFIX = "\$r8\$lambda\$"

/** Minimal reader for the parts of the DEX format used above */
private class DexReader(dex: ByteArray) {
    private val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)

    private val stringIdsOffset = buffer.getInt(0x3C)
    private val typeIdsOffset = buffer.getInt(0x44)
    private val protoIdsOffset = buffer.getInt(0x4C)
    private val methodIdsOffset = buffer.getInt(0x5C)
    private val classDefsSize = buffer.getInt(0x60)
    private val classDefsOffset = buffer.getInt(0x64)

    class MethodReference(val owner: String, val name: String, val descriptor: String)
    class EncodedMethod(val methodIndex: Int, val accessFlags: Int, val codeOffset: Int)
    class ClassDefinition(val descriptor: String, val methods: List<EncodedMethod>)

    fun string(index: Int): String {
        var position = buffer.getInt(stringIdsOffset + index * 4)
        while (buffer.get(position).toInt() and 0x80 != 0) position++ // utf16 length (uleb128)
        position++
        val start = position
        while (buffer.get(position).toInt() != 0) position++
        /* MUTF-8: identifiers in class files are ASCII compatible */
        return String(buffer.array(), start, position - start, Charsets.UTF_8)
    }

    fun type(index: Int): String = string(buffer.getInt(typeIdsOffset + index * 4))

    private fun protoDescriptor(index: Int): String {
        val offset = protoIdsOffset + index * 12
        val returnType = type(buffer.getInt(offset + 4))
        val parametersOffset = buffer.getInt(offset + 8)
        val parameters = if (parametersOffset == 0) emptyList() else {
            val size = buffer.getInt(parametersOffset)
            (0 until size).map { type(buffer.getShort(parametersOffset + 4 + it * 2).toInt() and 0xFFFF) }
        }
        return parameters.joinToString("", "(", ")") + returnType
    }

    fun method(index: Int): MethodReference {
        val offset = methodIdsOffset + index * 8
        val owner = type(buffer.getShort(offset).toInt() and 0xFFFF)
        val proto = buffer.getShort(offset + 2).toInt() and 0xFFFF
        val name = string(buffer.getInt(offset + 4))
        return MethodReference(owner, name, protoDescriptor(proto))
    }

    fun classDefinitions(): List<ClassDefinition> = (0 until classDefsSize).map { index ->
        val offset = classDefsOffset + index * 32
        val descriptor = type(buffer.getInt(offset))
        val classDataOffset = buffer.getInt(offset + 24)
        ClassDefinition(descriptor, if (classDataOffset == 0) emptyList() else readMethods(classDataOffset))
    }

    private fun readMethods(classDataOffset: Int): List<EncodedMethod> {
        var position = classDataOffset
        fun uleb(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = buffer.get(position++).toInt()
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        val staticFields = uleb()
        val instanceFields = uleb()
        val directMethods = uleb()
        val virtualMethods = uleb()
        repeat((staticFields + instanceFields) * 2) { uleb() }

        val methods = mutableListOf<EncodedMethod>()
        listOf(directMethods, virtualMethods).forEach { count ->
            var methodIndex = 0
            repeat(count) {
                methodIndex += uleb()
                val access = uleb()
                val code = uleb()
                methods += EncodedMethod(methodIndex, access, code)
            }
        }
        return methods
    }

    /** The first 'invoke-*' of a method: accessor bodies are 'new-instance'?, 'invoke-*', 'move-result'?, 'return*' */
    fun firstInvoke(codeOffset: Int): LambdaAccessor.Target? {
        if (codeOffset == 0) return null
        val size = buffer.getInt(codeOffset + 12)
        val instructions = codeOffset + 16
        var unit = 0
        while (unit < size) {
            val opcode = buffer.getShort(instructions + unit * 2).toInt() and 0xFF
            val kind = when (opcode) {
                0x6e, 0x74 -> LambdaAccessor.Kind.Virtual
                0x70, 0x76 -> LambdaAccessor.Kind.Direct
                0x71, 0x77 -> LambdaAccessor.Kind.Static
                0x72, 0x78 -> LambdaAccessor.Kind.Interface
                else -> null
            }
            if (kind != null) {
                val reference = method(buffer.getShort(instructions + (unit + 1) * 2).toInt() and 0xFFFF)
                val owner = reference.owner.removePrefix("L").removeSuffix(";")
                return LambdaAccessor.Target(owner, reference.name, reference.descriptor, kind)
            }
            unit += when (opcode) {
                0x22 /* new-instance */, 0x02, 0x05, 0x08 /* move/from16 */ -> 2
                0x01, 0x04, 0x07 /* move */, 0x0a, 0x0b, 0x0c /* move-result */, 0x0e, 0x0f, 0x10, 0x11 /* return */ -> 1
                else -> return null
            }
        }
        return null
    }
}
