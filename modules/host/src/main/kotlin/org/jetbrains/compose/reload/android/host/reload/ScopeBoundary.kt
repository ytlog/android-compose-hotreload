package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.InstructionToken
import org.jetbrains.compose.reload.analysis.InstructionTree
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*

/** Exact executable parent code with opaque child boundaries; never compare child slot positions. */
internal fun ownScopeCode(method: MethodNode, tree: InstructionTree): List<String>? {
    if (tree.failure != null || method.tryCatchBlocks.orEmpty().isNotEmpty()) return null
    val own = tree.tokens.filterNot {
        it is InstructionToken.SourceInformation || it is InstructionToken.SourceInformationMarkerStart ||
            it is InstructionToken.SourceInformationMarkerEnd
    }.flatMap { it.instructions }
    val targets = own.flatMap {
        when (it) {
            is JumpInsnNode -> listOf(it.label)
            is TableSwitchInsnNode -> it.labels + it.dflt
            is LookupSwitchInsnNode -> it.labels + it.dflt
            else -> emptyList()
        }
    }.toSet()
    if (!own.containsAll(targets)) return null // Cross-boundary control flow cannot be isolated.
    val labels = own.filterIsInstance<LabelNode>().filter { it in targets }.withIndex().associate { it.value to it.index }
    fun first(tree: InstructionTree): Int =
        (tree.tokens.flatMap { it.instructions }.map(method.instructions::indexOf) + tree.children.map(::first)).minOrNull() ?: -1
    val events = own.map { method.instructions.indexOf(it) to it } + tree.children.map { first(it) to it }
    if (events.any { it.first < 0 }) return null
    return events.sortedBy { it.first }.mapNotNull { (_, event) ->
        when (event) {
            is InstructionTree -> "child:${event.type}"
            is LabelNode -> labels[event]?.let { "label:$it" }
            is LineNumberNode, is FrameNode -> null
            is InsnNode -> "op:${event.opcode}"
            is VarInsnNode -> "var:${event.opcode}:${event.`var`}"
            is IntInsnNode -> "int:${event.opcode}:${event.operand}"
            is TypeInsnNode -> "type:${event.opcode}:${event.desc}"
            is FieldInsnNode -> "field:${event.opcode}:${event.owner}:${event.name}:${event.desc}"
            is MethodInsnNode -> "call:${event.opcode}:${event.owner}:${event.name}:${event.desc}:${event.itf}"
            is JumpInsnNode -> "jump:${event.opcode}:${labels.getValue(event.label)}"
            is IincInsnNode -> "inc:${event.`var`}:${event.incr}"
            is LdcInsnNode -> {
                val next = generateSequence(event.next) { it.next }.firstOrNull { it.opcode >= 0 }
                if (event.cst is String && next is MethodInsnNode && next.opcode == INVOKESTATIC &&
                    next.owner == "androidx/compose/runtime/ComposerKt" && next.name == "traceEventStart" &&
                    next.desc == "(IIILjava/lang/String;)V") "trace-source"
                else "constant:${event.cst.javaClass.name}:${event.cst}"
            }
            is InvokeDynamicInsnNode -> "indy:${event.name}:${event.desc}:${event.bsm}:${event.bsmArgs.toList()}"
            is TableSwitchInsnNode -> "table:${event.min}:${event.max}:${labels.getValue(event.dflt)}:${event.labels.map(labels::getValue)}"
            is LookupSwitchInsnNode -> "lookup:${event.keys}:${labels.getValue(event.dflt)}:${event.labels.map(labels::getValue)}"
            is MultiANewArrayInsnNode -> "array:${event.desc}:${event.dims}"
            else -> return null
        }
    }
}
