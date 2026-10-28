package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue

/** Proven operand facts only; ambiguous merges and unanalysable bytecode keep conservative edges. */
internal class CallOperands {
    // A method has several Compose scopes, but its control-flow frames need computing only once.
    private val last = ThreadLocal<Pair<MethodNode, Map<MethodInsnNode, ExecutedCall>>>()

    fun analyze(id: MethodId, node: MethodNode): Map<MethodInsnNode, ExecutedCall> {
        last.get()?.takeIf { it.first === node }?.let { return it.second }
        val calls = runCatching {
            val parameters = mutableMapOf<Int, Int>()
            var slot = if (node.access and ACC_STATIC == 0) 1 else 0
            if (slot == 1) parameters[0] = -1
            Type.getArgumentTypes(node.desc).forEachIndexed { index, type -> parameters[slot] = index; slot += type.size }
            val interpreter = object : BasicInterpreter(ASM9) {
                override fun newParameterValue(instance: Boolean, local: Int, type: Type): BasicValue =
                    if (type.sort == Type.OBJECT || type.sort == Type.ARRAY) ParameterValue(type, parameters.getValue(local))
                    else super.newParameterValue(instance, local, type)
                override fun newValue(type: Type?): BasicValue? = when (type?.sort) {
                    Type.OBJECT, Type.ARRAY -> BasicValue(type)
                    else -> super.newValue(type)
                }
                override fun newOperation(insn: AbstractInsnNode): BasicValue =
                    if (insn.opcode == NEW) ExactValue(Type.getObjectType((insn as TypeInsnNode).desc))
                    else super.newOperation(insn)

                override fun unaryOperation(insn: AbstractInsnNode, value: BasicValue): BasicValue? =
                    // A cast keeps the same object. Its previous non-Object bound is still
                    // valid, including widening Set/List -> Iterable casts emitted by Kotlin.
                    if (insn.opcode == CHECKCAST && value.type?.sort == Type.OBJECT &&
                        value.type.internalName != "java/lang/Object") value
                    else if (insn.opcode == CHECKCAST && value is ParameterValue)
                        ParameterValue(Type.getObjectType((insn as TypeInsnNode).desc), value.parameter)
                    else super.unaryOperation(insn, value)

                override fun naryOperation(insn: AbstractInsnNode, values: MutableList<out BasicValue>): BasicValue? {
                    if (insn is InvokeDynamicInsnNode) {
                        lambdaCalls(insn)?.let { return CallbackValue(Type.getReturnType(insn.desc), it) }
                    }
                    return super.naryOperation(insn, values)
                }

                override fun binaryOperation(insn: AbstractInsnNode, left: BasicValue, right: BasicValue): BasicValue? =
                    if (insn.opcode == AALOAD && left.type?.sort == Type.ARRAY)
                        newValue(Type.getType(left.type.descriptor.substring(1)))
                    else super.binaryOperation(insn, left, right)

                override fun merge(left: BasicValue, right: BasicValue): BasicValue = when {
                    left is ParameterValue && right is ParameterValue && left.parameter == right.parameter ->
                        ParameterValue(if (left.type == right.type) left.type else Type.getObjectType("java/lang/Object"), left.parameter)
                    left is CallbackValue && right is CallbackValue && left.type == right.type ->
                        CallbackValue(left.type, left.callbacks + right.callbacks)
                    left is ExactValue && right is ExactValue && left.type == right.type -> left
                    left.type == right.type -> BasicValue(left.type)
                    else -> super.merge(left, right)
                }
            }
            val frames = Analyzer(interpreter).analyze(id.classId.value, node)
            node.instructions.toArray().mapIndexedNotNull { index, instruction ->
                val call = instruction as? MethodInsnNode ?: return@mapIndexedNotNull null
                val frame = frames[index] ?: return@mapIndexedNotNull null
                val arguments = Type.getArgumentTypes(call.desc)
                val start = frame.stackSize - arguments.size
                val virtual = call.opcode == INVOKEVIRTUAL || call.opcode == INVOKEINTERFACE
                val receiver = if (virtual) frame.getStack(start - 1).type else null
                val nulls = arguments.indices.filterTo(mutableSetOf()) {
                    frame.getStack(start + it).type == BasicInterpreter.NULL_TYPE
                }
                val argumentTypes = arguments.indices.mapNotNull { index ->
                    frame.getStack(start + index).type?.takeIf {
                        it.sort == Type.OBJECT && it != BasicInterpreter.NULL_TYPE && it.internalName != "java/lang/Object"
                    }?.let { index to ClassId(it.internalName) }
                }.toMap()
                val callbacks = arguments.indices.mapNotNull { index ->
                    (frame.getStack(start + index) as? CallbackValue)?.let { index to it.callbacks }
                }.toMap()
                val parameterArguments = arguments.indices.mapNotNull { index ->
                    (frame.getStack(start + index) as? ParameterValue)?.let { index to it.parameter }
                }.toMap()
                call to ExecutedCall(MethodId(ClassId(call.owner), call.name, call.desc), virtual,
                    receiver?.takeIf { it.sort == Type.OBJECT && it != BasicInterpreter.NULL_TYPE &&
                        it.internalName != "java/lang/Object" }
                        ?.let { ClassId(it.internalName) }, nulls, argumentTypes, callbacks,
                    if (virtual) (frame.getStack(start - 1) as? CallbackValue)?.callbacks else null,
                    parameterArguments = parameterArguments,
                    receiverParameter = if (virtual) (frame.getStack(start - 1) as? ParameterValue)?.parameter else null)
            }.toMap()
        }.getOrDefault(emptyMap())
        last.set(node to calls)
        return calls
    }
}

private class ExactValue(type: Type) : BasicValue(type) {
    override fun equals(other: Any?): Boolean = other is ExactValue && type == other.type
    override fun hashCode(): Int = 31 * type.hashCode() + 1
}

private class CallbackValue(type: Type, val callbacks: Set<DeferredCall>) : BasicValue(type) {
    override fun equals(other: Any?): Boolean = other is CallbackValue && type == other.type && callbacks == other.callbacks
    override fun hashCode(): Int = 31 * type.hashCode() + callbacks.hashCode()
}

private class ParameterValue(type: Type, val parameter: Int) : BasicValue(type) {
    override fun equals(other: Any?): Boolean = other is ParameterValue && type == other.type && parameter == other.parameter
    override fun hashCode(): Int = 31 * type.hashCode() + parameter
}
