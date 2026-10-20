package org.jetbrains.compose.reload.android.host.dex

import org.jetbrains.compose.reload.analysis.ClassId
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Restores the JVM semantics of 'invokedynamic' lambdas for redefined classes on ART.
 *
 * On the JVM, lambdas are spun as anonymous classes when the call site links: a redefined class gets fresh ones.
 * D8 instead desugars lambdas into named classes ('Foo$$ExternalSyntheticLambda0', numbered by occurrence).
 * Adding a lambda therefore re-numbers them, and redefining 'Lambda1' with another functional interface
 * is a hierarchy change ART rejects.
 *
 * The lambda call sites of a redefined class are moved to a helper class with a unique name per reload
 * ('Foo$chr$lambdas$3'). D8 creates the synthetic lambda classes for the helper: they are always new classes.
 *
 * Lambda instances created before the reload call the '$r8$lambda$' [accessors] D8 generated in this class
 * (for private implementation methods). They are kept as regular methods with the very same names,
 * so these instances keep working (and run the new code, as on the JVM). The helper's lambdas use them as well.
 *
 * The analysis still runs on the original bytecode: this only affects what is converted to DEX.
 */
internal fun isolateLambdas(
    classId: ClassId, bytecode: ByteArray, generation: Int, accessors: List<LambdaAccessor>
): Map<ClassId, ByteArray> {
    val node = ClassNode()
    ClassReader(bytecode).accept(node, 0)

    val isInterface = node.access and Opcodes.ACC_INTERFACE != 0

    val callSites = node.methods.flatMap { method ->
        method.instructions.filterIsInstance<InvokeDynamicInsnNode>()
            .filter { it.bsm.owner == LAMBDA_METAFACTORY }
            .map { method to it }
    }
    if (callSites.isEmpty()) return mapOf(classId to bytecode)

    val helperName = "${node.name}\$chr\$lambdas\$$generation"
    val helper = ClassNode().apply {
        version = node.version
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
        name = helperName
        superName = "java/lang/Object"
        sourceFile = node.sourceFile
    }

    accessors.forEach { accessor ->
        if (node.methods.none { it.name == accessor.name && it.desc == accessor.descriptor }) {
            node.methods.add(accessorMethod(accessor, node))
        }
    }

    callSites.forEachIndexed { index, (method, callSite) ->
        val factoryName = "lambda$$index"
        val bootstrapArguments = callSite.bsmArgs.map { argument ->
            if (argument !is Handle || argument.owner != node.name) return@map argument
            val accessor = accessors.firstOrNull { it.target.matches(argument) } ?: return@map argument
            Handle(Opcodes.H_INVOKESTATIC, node.name, accessor.name, accessor.descriptor, isInterface)
        }.toTypedArray()

        /* static <captures> -> <functional interface>: the same stack effect as the original call site */
        val factory = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, factoryName, callSite.desc, null, null
        )
        var slot = 0
        Type.getArgumentTypes(callSite.desc).forEach { type ->
            factory.instructions.add(VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot))
            slot += type.size
        }
        factory.instructions.add(InvokeDynamicInsnNode(callSite.name, callSite.desc, callSite.bsm, *bootstrapArguments))
        factory.instructions.add(InsnNode(Type.getReturnType(callSite.desc).getOpcode(Opcodes.IRETURN)))
        helper.methods.add(factory)

        method.instructions.set(
            callSite, MethodInsnNode(Opcodes.INVOKESTATIC, helperName, factoryName, callSite.desc, false)
        )
    }

    return mapOf(
        classId to ClassWriter(ClassWriter.COMPUTE_MAXS).also { node.accept(it) }.toByteArray(),
        ClassId(helperName) to ClassWriter(ClassWriter.COMPUTE_MAXS).also { helper.accept(it) }.toByteArray(),
    )
}

private const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"

private fun LambdaAccessor.Target.matches(handle: Handle): Boolean {
    if (owner != handle.owner || name != handle.name || descriptor != handle.desc) return false
    return when (handle.tag) {
        Opcodes.H_INVOKESTATIC -> kind == LambdaAccessor.Kind.Static
        Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> kind == LambdaAccessor.Kind.Direct
        Opcodes.H_INVOKEVIRTUAL -> kind == LambdaAccessor.Kind.Virtual
        Opcodes.H_INVOKEINTERFACE -> kind == LambdaAccessor.Kind.Interface
        else -> false
    }
}

/** The JVM equivalent of the accessor D8 generated */
private fun accessorMethod(accessor: LambdaAccessor, owner: ClassNode): MethodNode {
    val isInterface = owner.access and Opcodes.ACC_INTERFACE != 0
    /* Interface bridges must be public static, never protected/final. Their targets may be private/static. */
    val jvmFlags = if (isInterface) Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC else
        Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
    val method = MethodNode(accessor.access and jvmFlags or Opcodes.ACC_STATIC, accessor.name, accessor.descriptor, null, null)
    val target = accessor.target
    val isConstructor = target.name == "<init>"
    if (isConstructor) {
        method.instructions.add(TypeInsnNode(Opcodes.NEW, target.owner))
        method.instructions.add(InsnNode(Opcodes.DUP))
    }
    var slot = 0
    Type.getArgumentTypes(accessor.descriptor).forEach { type ->
        method.instructions.add(VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot))
        slot += type.size
    }
    val opcode = when (target.kind) {
        LambdaAccessor.Kind.Static -> Opcodes.INVOKESTATIC
        LambdaAccessor.Kind.Direct -> Opcodes.INVOKESPECIAL
        LambdaAccessor.Kind.Virtual -> Opcodes.INVOKEVIRTUAL
        LambdaAccessor.Kind.Interface -> Opcodes.INVOKEINTERFACE
    }
    method.instructions.add(
        MethodInsnNode(opcode, target.owner, target.name, target.descriptor,
            (isInterface && target.owner == owner.name) || target.kind == LambdaAccessor.Kind.Interface)
    )
    method.instructions.add(InsnNode(Type.getReturnType(accessor.descriptor).getOpcode(Opcodes.IRETURN)))
    return method
}
