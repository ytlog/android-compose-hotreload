package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.FieldId
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.InstructionTree
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.analysis.ScopeAnalyzerExtension
import org.jetbrains.compose.reload.core.Context
import org.jetbrains.compose.reload.core.with
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Handle
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.AbstractInsnNode

internal data class ExecutedCall(
    val method: MethodId,
    val virtual: Boolean,
    val receiverType: ClassId? = null,
    val nullArguments: Set<Int> = emptySet(),
    val argumentTypes: Map<Int, ClassId> = emptyMap(),
    val argumentCallbacks: Map<Int, Set<DeferredCall>> = emptyMap(),
    val receiverCallbacks: Set<DeferredCall>? = null,
    val externalArguments: Boolean = false,
    val parameterArguments: Map<Int, Int> = emptyMap(),
    val receiverParameter: Int? = null,
)
internal data class DeferredCall(val invocation: MethodId, val call: ExecutedCall)

/** JVM invocation edges, excluding lambda implementation handles (creation is not invocation). */
internal data class MethodExecutionCode(
    val calls: Set<ExecutedCall>,
    val fields: Set<FieldId>,
    val initializers: Set<ClassId>,
    val deferred: Set<DeferredCall>,
    val known: Boolean,
    val bridgeCasts: Map<Int, ClassId> = emptyMap(),
) {
    companion object Key : Context.Key.Optional<MethodExecutionCode>()
}

internal data class ComposeScopeCode(
    val hasCache: Boolean,
    val directCalls: Set<ExecutedCall>,
    val initializers: Set<ClassId>,
    val fields: Set<FieldId>,
    val parsed: Boolean,
    val ownCode: List<String>? = null,
) {
    companion object Key : Context.Key.Optional<ComposeScopeCode>()
}

/** Keep cache evaluation dependencies separate from the bodies of cached lambda objects. */
class ComposeScopeAnalyzer : ScopeAnalyzerExtension {
    private val operands = CallOperands()

    override fun analyze(methodId: MethodId, methodNode: MethodNode, tree: InstructionTree): Context {
        val callSites = operands.analyze(methodId, methodNode)
        fun call(node: MethodInsnNode) = callSites[node] ?: ExecutedCall(
            MethodId(ClassId(node.owner), node.name, node.desc), node.opcode == INVOKEVIRTUAL || node.opcode == INVOKEINTERFACE)
        val instructions = tree.tokens.flatMap { it.instructions }
        val calls = instructions.filterIsInstance<MethodInsnNode>()
        val cacheCalls = calls.filter { (it.owner in setOf("androidx/compose/runtime/Composer", "androidx/compose/runtime/ComposerKt") &&
            it.name in setOf("rememberedValue", "updateRememberedValue", "cache")) ||
            (it.owner == "androidx/compose/runtime/ComposablesKt" && it.name == "remember") }
        val methodCode = methodNode.instructions.toList()
        fun ranges(prefix: String) = methodNode.localVariables.orEmpty().filter { it.name.startsWith(prefix) }.map {
            methodNode.instructions.indexOf(it.start) until methodNode.instructions.indexOf(it.end)
        }
        val outer = if (cacheCalls.isEmpty()) emptyList() else ranges("\$i\$f\$cache")
        val calculations = if (cacheCalls.isEmpty()) emptyList() else ranges("\$i\$a\$-cache-")
        var parsed = tree.failure == null
        val evaluation = cacheCalls.flatMap { call ->
            val offset = methodNode.instructions.indexOf(call)
            val cache = outer.filter { offset in it }.minByOrNull { it.last - it.first }
            val bodies = calculations.filter { cache != null && it.first in cache && it.last in cache }
            if (bodies.isEmpty()) parsed = false // Stripped/unknown cache debug ranges: retain reset semantics.
            bodies.flatMap { range -> methodCode.subList(range.first, range.last + 1) }
        }
        val dynamic = methodCode.filterIsInstance<InvokeDynamicInsnNode>()
        val execution = MethodExecutionCode(
            calls = methodCode.filterIsInstance<MethodInsnNode>().map(::call).toSet(),
            fields = methodCode.filterIsInstance<FieldInsnNode>().filter { it.opcode == GETFIELD || it.opcode == GETSTATIC }
                .map { FieldId(ClassId(it.owner), it.name, it.desc) }.toSet(),
            initializers = initializers(methodCode),
            deferred = dynamic.flatMap { lambdaCalls(it).orEmpty() }.toSet(),
            known = dynamic.all { it.bsm.owner in setOf("java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory") },
            bridgeCasts = bridgeCasts(methodNode),
        )
        return Context(MethodExecutionCode with execution, ComposeScopeCode with ComposeScopeCode(
            hasCache = cacheCalls.isNotEmpty(),
            directCalls = evaluation.filterIsInstance<MethodInsnNode>().map(::call).toSet(),
            initializers = initializers(evaluation),
            fields = evaluation.filterIsInstance<FieldInsnNode>().map { FieldId(ClassId(it.owner), it.name, it.desc) }.toSet(),
            parsed = parsed,
            ownCode = ownScopeCode(methodNode, tree),
        ))
    }
}

/** Casts before the first executable call in a bridge; an impossible cast cannot reach its body. */
private fun bridgeCasts(method: MethodNode): Map<Int, ClassId> {
    if (method.access and ACC_BRIDGE == 0) return emptyMap()
    val arguments = mutableMapOf<Int, Int>()
    var slot = if (method.access and ACC_STATIC == 0) 1 else 0
    Type.getArgumentTypes(method.desc).forEachIndexed { index, type -> arguments[slot] = index; slot += type.size }
    val casts = mutableMapOf<Int, ClassId>()
    var loaded: Int? = null
    for (instruction in method.instructions) {
        if (instruction.opcode < 0) continue
        when (instruction.opcode) {
            ALOAD -> loaded = arguments[(instruction as org.objectweb.asm.tree.VarInsnNode).`var`]
            CHECKCAST -> loaded?.let { casts[it] = ClassId((instruction as TypeInsnNode).desc) }
            else -> break
        }
    }
    return casts
}

internal fun lambdaCalls(indy: InvokeDynamicInsnNode): Set<DeferredCall>? {
    if (indy.bsm.owner != "java/lang/invoke/LambdaMetafactory") return null
    return indy.bsmArgs.filterIsInstance<Handle>().mapTo(mutableSetOf()) {
        DeferredCall(MethodId(ClassId(Type.getReturnType(indy.desc).internalName), indy.name,
            (indy.bsmArgs.first() as Type).descriptor),
            ExecutedCall(MethodId(ClassId(it.owner), it.name, it.desc),
                it.tag == H_INVOKEVIRTUAL || it.tag == H_INVOKEINTERFACE))
    }
}

private fun initializers(code: List<AbstractInsnNode>): Set<ClassId> = code.mapNotNull {
    when {
        it is TypeInsnNode && it.opcode == NEW -> ClassId(it.desc)
        it is FieldInsnNode && it.opcode == GETSTATIC -> ClassId(it.owner)
        it is MethodInsnNode && it.opcode == INVOKESTATIC -> ClassId(it.owner)
        else -> null
    }
}.toSet()
