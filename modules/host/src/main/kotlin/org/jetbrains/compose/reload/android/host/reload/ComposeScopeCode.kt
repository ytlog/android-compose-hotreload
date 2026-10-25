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

internal data class ExecutedCall(val method: MethodId, val virtual: Boolean)
internal data class DeferredCall(val type: ClassId, val call: ExecutedCall)

/** JVM invocation edges, excluding lambda implementation handles (creation is not invocation). */
internal data class MethodExecutionCode(
    val calls: Set<ExecutedCall>,
    val fields: Set<FieldId>,
    val initializers: Set<ClassId>,
    val deferred: Set<DeferredCall>,
    val known: Boolean,
) {
    companion object Key : Context.Key.Optional<MethodExecutionCode>()
}

internal data class ComposeScopeCode(
    val hasCache: Boolean,
    val directCalls: Set<ExecutedCall>,
    val initializers: Set<ClassId>,
    val fields: Set<FieldId>,
    val parsed: Boolean,
) {
    companion object Key : Context.Key.Optional<ComposeScopeCode>()
}

/** Keep cache evaluation dependencies separate from the bodies of cached lambda objects. */
class ComposeScopeAnalyzer : ScopeAnalyzerExtension {
    override fun analyze(methodId: MethodId, methodNode: MethodNode, tree: InstructionTree): Context {
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
            calls = methodCode.filterIsInstance<MethodInsnNode>().map {
                ExecutedCall(MethodId(ClassId(it.owner), it.name, it.desc), it.opcode == INVOKEVIRTUAL || it.opcode == INVOKEINTERFACE)
            }.toSet(),
            fields = methodCode.filterIsInstance<FieldInsnNode>().filter { it.opcode == GETFIELD || it.opcode == GETSTATIC }
                .map { FieldId(ClassId(it.owner), it.name, it.desc) }.toSet(),
            initializers = initializers(methodCode),
            deferred = dynamic.filter { it.bsm.owner == "java/lang/invoke/LambdaMetafactory" }.flatMap { indy ->
                indy.bsmArgs.filterIsInstance<Handle>().map {
                    DeferredCall(ClassId(Type.getReturnType(indy.desc).internalName),
                        ExecutedCall(MethodId(ClassId(it.owner), it.name, it.desc),
                            it.tag == H_INVOKEVIRTUAL || it.tag == H_INVOKEINTERFACE))
                }
            }.toSet(),
            known = dynamic.all { it.bsm.owner in setOf("java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory") },
        )
        return Context(MethodExecutionCode with execution, ComposeScopeCode with ComposeScopeCode(
            hasCache = cacheCalls.isNotEmpty(),
            directCalls = evaluation.filterIsInstance<MethodInsnNode>().map {
                ExecutedCall(MethodId(ClassId(it.owner), it.name, it.desc), it.opcode == INVOKEVIRTUAL || it.opcode == INVOKEINTERFACE)
            }.toSet(),
            initializers = initializers(evaluation),
            fields = evaluation.filterIsInstance<FieldInsnNode>().map { FieldId(ClassId(it.owner), it.name, it.desc) }.toSet(),
            parsed = parsed,
        ))
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
