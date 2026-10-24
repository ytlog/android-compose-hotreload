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

internal data class ComposeScopeCode(
    val hasCache: Boolean,
    val directCalls: Set<MethodId>,
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
        return Context(ComposeScopeCode with ComposeScopeCode(
            hasCache = cacheCalls.isNotEmpty(),
            directCalls = evaluation.filterIsInstance<MethodInsnNode>().map { MethodId(ClassId(it.owner), it.name, it.desc) }.toSet(),
            fields = evaluation.filterIsInstance<FieldInsnNode>().map { FieldId(ClassId(it.owner), it.name, it.desc) }.toSet(),
            parsed = parsed,
        ))
    }
}
