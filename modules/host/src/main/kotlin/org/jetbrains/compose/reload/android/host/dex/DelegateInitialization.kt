package org.jetbrains.compose.reload.android.host.dex

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicVerifier
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId

private const val INITIALIZER = "org/jetbrains/compose/reload/android/DelegateInitialization"
private val stateFactories = mapOf(
    "androidx/compose/runtime/SnapshotStateKt.mutableStateOf" to "(Ljava/lang/Object;Landroidx/compose/runtime/SnapshotMutationPolicy;)Landroidx/compose/runtime/MutableState;",
    "androidx/compose/runtime/SnapshotStateKt.mutableStateOf\$default" to "(Ljava/lang/Object;Landroidx/compose/runtime/SnapshotMutationPolicy;ILjava/lang/Object;)Landroidx/compose/runtime/MutableState;",
    "androidx/compose/runtime/SnapshotIntStateKt.mutableIntStateOf" to "(I)Landroidx/compose/runtime/MutableIntState;",
    "androidx/compose/runtime/SnapshotLongStateKt.mutableLongStateOf" to "(J)Landroidx/compose/runtime/MutableLongState;",
    "androidx/compose/runtime/PrimitiveSnapshotStateKt.mutableFloatStateOf" to "(F)Landroidx/compose/runtime/MutableFloatState;",
    "androidx/compose/runtime/SnapshotDoubleStateKt.mutableDoubleStateOf" to "(D)Landroidx/compose/runtime/MutableDoubleState;",
)

/**
 * ART supplies null for an added field on an existing object. Repair only constant Compose state
 * factories, on first access, without replaying constructors or any application initializer call.
 * Original class bytes remain the analysis/session source of truth; guards are emitted every reload.
 */
internal fun initializeConstantDelegates(bytes: ByteArray): ByteArray {
    val node = readClass(bytes)
    val recipes = supportedDelegates(node)
    if (recipes.isEmpty()) return bytes
    node.methods.filter { it.name != "<init>" }.forEach { method ->
        method.instructions.toList().filterIsInstance<FieldInsnNode>().filter {
            it.opcode == GETFIELD && it.owner == node.name && it.name in recipes
        }.forEach { read ->
            // Keep GETFIELD itself: null receiver and retired-field errors still belong to ART.
            // No branch or extra synthetic method changes existing frames or retained old calls.
            method.instructions.insertBefore(read, InsnNode(DUP))
            method.instructions.insert(read, InsnList().apply {
                add(LdcInsnNode(Type.getObjectType(node.name))); add(LdcInsnNode(read.name))
                add(LdcInsnNode(recipes.getValue(read.name)))
                add(MethodInsnNode(INVOKESTATIC, INITIALIZER, "read",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false))
                add(TypeInsnNode(CHECKCAST, Type.getType(read.desc).internalName))
            })
        }
    }
    return ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()
}

private fun supportedDelegates(node: ClassNode): Map<String, String> {
    if (node.access and ACC_INTERFACE != 0 ||
        node.visibleAnnotations.orEmpty().none { it.desc == "Lkotlin/Metadata;" }) return emptyMap()
    val constructors = node.methods.filter { it.name == "<init>" }
    return node.fields.filter { it.name.endsWith("\$delegate") && it.access and ACC_STATIC == 0 }.mapNotNull { field ->
        val writes = constructors.flatMap { constructor ->
            constructor.instructions.toList().filterIsInstance<FieldInsnNode>().filter {
                it.opcode == PUTFIELD && it.owner == node.name && it.name == field.name && it.desc == field.desc
            }
        }
        if (writes.isEmpty()) return@mapNotNull null
        val recipes = writes.map { constantRecipe(it, field.desc) ?: return@mapNotNull null }
        if (recipes.map(::recipeKey).distinct().size != 1) return@mapNotNull null
        if (constructors.any { constructor ->
            val code = constructor.instructions.toList()
            val delegates = code.filterIsInstance<MethodInsnNode>().any {
                it.opcode == INVOKESPECIAL && it.owner == node.name && it.name == "<init>"
            }
            !delegates && writes.count { it in code } != 1
        }) return@mapNotNull null
        val check = MethodNode(ACC_STATIC, "recipe", "()${field.desc}", null, null)
        recipes.first().forEach { check.instructions.add(it.clone(emptyMap())) }
        check.instructions.add(InsnNode(ARETURN)); check.maxStack = 16
        if (runCatching { Analyzer(BasicVerifier()).analyze(node.name, check) }.isFailure) return@mapNotNull null
        field.name to (encodeRecipe(recipes.first()) ?: return@mapNotNull null)
    }.toMap()
}

private fun constantRecipe(write: FieldInsnNode, descriptor: String): List<AbstractInsnNode>? {
    val reverse = mutableListOf<AbstractInsnNode>()
    var current = write.previous
    while (current != null && reverse.size < 16) {
        if (current is VarInsnNode && current.opcode == ALOAD && current.`var` == 0) break
        if (current.opcode >= 0) {
            if (!constantInstruction(current)) return null
            reverse += current
        }
        current = current.previous
    }
    if (current !is VarInsnNode || current.opcode != ALOAD || current.`var` != 0) return null
    val recipe = reverse.asReversed()
    val factory = recipe.lastOrNull() as? MethodInsnNode ?: return null
    if (stateFactories[factory.owner + "." + factory.name] != factory.desc ||
        Type.getReturnType(factory.desc).descriptor != descriptor) return null
    if (recipe.filterIsInstance<MethodInsnNode>().count { it.owner + "." + it.name in stateFactories } != 1) return null
    return recipe
}

private fun constantInstruction(node: AbstractInsnNode): Boolean = when (node) {
    is InsnNode -> node.opcode in ACONST_NULL..DCONST_1
    is IntInsnNode -> node.opcode == BIPUSH || node.opcode == SIPUSH
    is LdcInsnNode -> node.cst is String || node.cst is Number
    is MethodInsnNode -> node.opcode == INVOKESTATIC && (
        stateFactories[node.owner + "." + node.name] == node.desc ||
            (node.owner == "androidx/compose/runtime/SnapshotStateKt" &&
                node.name in setOf("structuralEqualityPolicy", "referentialEqualityPolicy", "neverEqualPolicy") &&
                node.desc == "()Landroidx/compose/runtime/SnapshotMutationPolicy;") ||
            node.name == "valueOf" && node.desc == when (node.owner) {
                "java/lang/Integer" -> "(I)Ljava/lang/Integer;"
                "java/lang/Long" -> "(J)Ljava/lang/Long;"
                "java/lang/Float" -> "(F)Ljava/lang/Float;"
                "java/lang/Double" -> "(D)Ljava/lang/Double;"
                "java/lang/Boolean" -> "(Z)Ljava/lang/Boolean;"
                "java/lang/Byte" -> "(B)Ljava/lang/Byte;"
                "java/lang/Short" -> "(S)Ljava/lang/Short;"
                "java/lang/Character" -> "(C)Ljava/lang/Character;"
                else -> ""
            })
    else -> false
}

private fun recipeKey(recipe: List<AbstractInsnNode>) = recipe.map {
    when (it) {
        is LdcInsnNode -> listOf(it.opcode, it.cst.javaClass.name, it.cst)
        is IntInsnNode -> listOf(it.opcode, it.operand)
        is MethodInsnNode -> listOf(it.opcode, it.owner, it.name, it.desc)
        else -> listOf(it.opcode)
    }
}

/** Only added supported delegate stores may differ; all previous constructor execution still matters. */
internal fun delegateCompatibleConstructors(beforeBytes: ByteArray, afterBytes: ByteArray): Set<MethodId> {
    val before = readClass(beforeBytes); val after = readClass(afterBytes)
    if (before.name != after.name || before.superName != after.superName || before.interfaces != after.interfaces ||
        before.access != after.access) return emptySet()
    fun fieldKey(field: FieldNode) = listOf(field.name, field.desc, field.access, field.value)
    if (!after.fields.map(::fieldKey).containsAll(before.fields.map(::fieldKey))) return emptySet()
    val added = after.fields.filter { field -> before.fields.none { it.name == field.name } }
    if (added.isEmpty() || after.fields.size != before.fields.size + added.size) return emptySet()
    val guarded = supportedDelegates(after)
    if (added.any { it.name !in guarded }) return emptySet()
    return after.methods.filter { it.name == "<init>" }.mapNotNullTo(mutableSetOf()) { constructor ->
        val previous = before.methods.singleOrNull { it.name == "<init>" && it.desc == constructor.desc }
            ?: return@mapNotNullTo null
        val stripped = copyMethod(constructor)
        stripped.instructions.toList().filterIsInstance<FieldInsnNode>().filter { write ->
            write.opcode == PUTFIELD && write.owner == after.name && added.any { it.name == write.name }
        }.forEach { write ->
            val recipe = constantRecipe(write, write.desc) ?: return@mapNotNullTo null
            var receiver = recipe.first().previous
            while (receiver != null && receiver.opcode < 0) receiver = receiver.previous
            if (receiver !is VarInsnNode || receiver.opcode != ALOAD || receiver.`var` != 0) return@mapNotNullTo null
            stripped.instructions.remove(receiver)
            recipe.forEach(stripped.instructions::remove); stripped.instructions.remove(write)
        }
        if (constructorCode(previous) == constructorCode(stripped)) MethodId(ClassId(after.name), "<init>", constructor.desc)
        else null
    }
}

private fun constructorCode(method: MethodNode): List<Byte> {
    val normalized = copyMethod(method).apply {
        localVariables = null; parameters = null
        instructions.toList().filter { it is LineNumberNode || it is FrameNode }.forEach(instructions::remove)
        maxStack = 0; maxLocals = 0
    }
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(V11, ACC_PUBLIC, "ConstructorComparison", null, "java/lang/Object", null)
    normalized.accept(writer); writer.visitEnd()
    return writer.toByteArray().toList()
}

private data class Policy(val name: String)

/** Interpret only the already validated literal/boxing/policy expression, never application code. */
private fun encodeRecipe(recipe: List<AbstractInsnNode>): String? = runCatching {
    val stack = mutableListOf<Any?>()
    recipe.dropLast(1).forEach { instruction ->
        when (instruction) {
            is LdcInsnNode -> stack += instruction.cst
            is IntInsnNode -> stack += instruction.operand
            is InsnNode -> stack += when (instruction.opcode) {
                ACONST_NULL -> null
                in ICONST_M1..ICONST_5 -> instruction.opcode - ICONST_0
                in LCONST_0..LCONST_1 -> (instruction.opcode - LCONST_0).toLong()
                in FCONST_0..FCONST_2 -> (instruction.opcode - FCONST_0).toFloat()
                in DCONST_0..DCONST_1 -> (instruction.opcode - DCONST_0).toDouble()
                else -> error("Not a scalar constant")
            }
            is MethodInsnNode -> if (instruction.name == "valueOf") {
                val value = stack.removeAt(stack.lastIndex)
                stack += when (instruction.owner) {
                    "java/lang/Boolean" -> (value as Int) != 0
                    "java/lang/Character" -> (value as Int).toChar()
                    "java/lang/Byte" -> (value as Int).toByte()
                    "java/lang/Short" -> (value as Int).toShort()
                    else -> value
                }
            } else stack += Policy(instruction.name)
            else -> error("Not a constant recipe")
        }
    }
    val factory = recipe.last() as MethodInsnNode
    val method = factory.name.substringBefore('$')
    val policy = if (method == "mutableStateOf") {
        if (factory.name.endsWith("\$default")) {
            require(stack.size == 4 && stack[3] == null && stack[2] in listOf(0, 2))
            if (stack[2] == 2) "structuralEqualityPolicy" else (stack[1] as Policy).name
        } else {
            require(stack.size == 2); (stack[1] as Policy).name
        }
    } else { require(stack.size == 1); "-" }
    val value = stack.first()
    require(value == null || value is String || value is Number || value is Boolean || value is Char)
    val literal = when (value) {
        is Float -> java.lang.Integer.toUnsignedString(value.toRawBits())
        is Double -> java.lang.Long.toUnsignedString(value.toRawBits())
        is Char -> value.code.toString()
        null -> ""
        else -> value.toString()
    }
    val encoded = java.util.Base64.getEncoder().encodeToString(literal.toByteArray(Charsets.UTF_8))
    "$method|$policy|${value?.javaClass?.simpleName ?: "null"}|$encoded"
}.getOrNull()
