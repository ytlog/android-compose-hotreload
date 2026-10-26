package org.jetbrains.compose.reload.android.host.dex

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

internal data class MethodKey(val name: String, val descriptor: String)
internal fun Handle.key() = MethodKey(name, desc)
internal fun MethodNode.key() = MethodKey(name, desc)

internal const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"
internal const val LAMBDA_PREFIX = "\$chr\$lambda\$"

internal fun ClassNode.lambdaSites(): List<Pair<MethodNode, InvokeDynamicInsnNode>> = methods.flatMap { method ->
    method.instructions.filterIsInstance<InvokeDynamicInsnNode>()
        .filter { it.bsm.owner == LAMBDA_METAFACTORY }.map { method to it }
}

internal fun InvokeDynamicInsnNode.implementation(): Handle = bsmArgs[1] as Handle

internal fun Handle.invocationType(): Type {
    val constructor = tag == Opcodes.H_NEWINVOKESPECIAL
    val arguments = Type.getArgumentTypes(desc).toList()
    val receiver = if (tag != Opcodes.H_INVOKESTATIC && !constructor) listOf(Type.getObjectType(owner)) else emptyList()
    return Type.getMethodType(if (constructor) Type.getObjectType(owner) else Type.getReturnType(desc),
        *(receiver + arguments).toTypedArray())
}

internal fun ClassNode.compilerLambda(handle: Handle): MethodNode? = methods.singleOrNull {
    handle.owner == name && it.key() == handle.key() &&
        (it.name.contains("\$lambda\$") || it.name.startsWith("lambda\$")) &&
        it.name != "\$deserializeLambda\$"
}

/** A missing name is opaque: it may be reused only with byte-identical implementation and ABI. */
internal data class Capture(val name: String?, val descriptor: String)

internal data class SourceLambda(
    val target: Handle,
    val method: MethodNode,
    val containers: Set<MethodKey>,
    val sam: String,
    val captures: List<Capture>,
    val fingerprint: List<Byte>,
) {
    val type: Type get() = target.invocationType()
}

internal fun sourceLambdas(node: ClassNode): List<SourceLambda> = node.lambdaSites()
    .filter { node.compilerLambda(it.second.implementation()) != null }
    .groupBy { it.second.implementation() }.map { (target, sites) ->
        val method = checkNotNull(node.compilerLambda(target))
        val captureCounts = sites.map { Type.getArgumentTypes(it.second.desc).size }.distinct()
        check(captureCounts.size == 1) { "Multiple capture ABIs for ${node.name}.${target.name}" }
        val samKeys = sites.map { (_, site) ->
            // Include instantiated SAM types, interface and altMetafactory flags/markers.
            site.name + Type.getReturnType(site.desc).descriptor + site.bsm.name + site.bsmArgs.filterNot { it is Handle }
                .joinToString("|") { it.toString() }
        }.distinct()
        check(samKeys.size == 1) { "Multiple SAM contracts for ${node.name}.${target.name}" }
        val types = target.invocationType().argumentTypes
        var slot = 0
        val captures = types.take(captureCounts.single()).mapIndexed { index, type ->
            val receiver = target.tag != Opcodes.H_INVOKESTATIC && index == 0
            val names = method.localVariables.orEmpty().filter { it.index == slot && it.desc == type.descriptor }
                .map { it.name }.distinct()
            val name = if (receiver) "@receiver" else names.singleOrNull()?.removePrefix("$")
            slot += type.size
            Capture(name, type.descriptor)
        }
        check(captures.mapNotNull { it.name }.distinct().size == captures.count { it.name != null }) {
            "Duplicate capture names in ${node.name}.${target.name}"
        }
        SourceLambda(target, method, sites.map { it.first.key() }.toSet(), samKeys.single(), captures,
            fingerprint(method, target.invocationType()))
    }

/** Ignore debug locations (including Compose source offsets) and numbered implementation names. */
private val composeSourceCalls = setOf(
    "sourceInformation" to "(Landroidx/compose/runtime/Composer;Ljava/lang/String;)V",
    "sourceInformationMarkerStart" to "(Landroidx/compose/runtime/Composer;ILjava/lang/String;)V",
    "traceEventStart" to "(IIILjava/lang/String;)V",
)

private fun fingerprint(method: MethodNode, type: Type): List<Byte> {
    val copy = copyMethod(method).apply {
        name = "body"
        desc = type.descriptor
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
        localVariables = null
        parameters = null
        visibleAnnotations = null
        invisibleAnnotations = null
        visibleParameterAnnotations = null
        invisibleParameterAnnotations = null
        attrs = null
        instructions.toArray().filter { it is LineNumberNode || it is FrameNode }.forEach(instructions::remove)
        // Inserting text earlier in a Kotlin file shifts sourceInformation in *every* generated
        // ComposableSingletons body. It is tooling metadata, not callback identity. Normalize
        // only an immediate literal at an exact compiler intrinsic; emitted bodies stay intact.
        instructions.filterIsInstance<MethodInsnNode>().filter { call ->
            call.opcode == Opcodes.INVOKESTATIC && call.owner == "androidx/compose/runtime/ComposerKt" &&
                (call.name to call.desc) in composeSourceCalls
        }.forEach { call ->
            val argument = generateSequence(call.previous) { it.previous }.firstOrNull { it.opcode >= 0 }
            if (argument is LdcInsnNode && argument.cst is String) argument.cst = "<compose-source-location>"
        }
    }
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LambdaBody", null, "java/lang/Object", null)
    copy.accept(writer)
    writer.visitEnd()
    return writer.toByteArray().toList()
}

internal fun copyMethod(method: MethodNode): MethodNode = MethodNode(
    method.access, method.name, method.desc, method.signature, method.exceptions?.toTypedArray()
).also { method.accept(it) }

internal fun readClass(code: ByteArray) = ClassNode().also { ClassReader(code).accept(it, 0) }

internal data class LambdaVersion(
    val id: Int,
    val scope: String,
    val source: SourceLambda,
    val body: MethodNode,
    val active: Boolean = true,
)

/** Match unchanged content first, then the sole remaining site in a scope/SAM group. */
internal fun matchLambdas(
    owner: String, sources: List<SourceLambda>, previous: List<LambdaVersion>, firstId: Int,
): Pair<List<Triple<SourceLambda, String, Int>>, Int> {
    val assigned = linkedMapOf<Handle, Triple<SourceLambda, String, Int>>()
    var nextId = firstId
    val byMethod = sources.associateBy { it.target.key() }
    while (assigned.size < sources.size) {
        val ready = sources.filter { it.target !in assigned && it.containers.all { key ->
            byMethod[key]?.target.let { it == null || it in assigned }
        } }
        check(ready.isNotEmpty()) { "Cyclic lambda factory contexts in $owner" }
        val groups = ready.groupBy { source ->
            val scope = source.containers.map { container ->
                byMethod[container]?.let { "lambda:${assigned.getValue(it.target).third}" }
                    ?: "method:${container.name}${container.descriptor}"
            }.sorted().joinToString("|")
            scope to source.sam
        }
        for ((group, candidates) in groups) {
            val (scope, sam) = group
            val old = previous.filter { it.active && it.scope == scope && it.source.sam == sam }.toMutableList()
            val new = candidates.toMutableList()
            fun pair(a: LambdaVersion, b: SourceLambda) {
                // With no capture names, changed code cannot establish retained binding identity.
                check((a.source.captures + b.captures).none { it.name == null } ||
                    a.source.fingerprint == b.fingerprint && a.source.captures == b.captures) {
                    "Capture debug names required to evolve lambda $owner in $scope"
                }
                assigned[b.target] = Triple(b, scope, a.id)
                old.remove(a)
                new.remove(b)
            }
            fun unique(predicate: (LambdaVersion, SourceLambda) -> Boolean) {
                var progress: Boolean
                do {
                    progress = false
                    for (a in old.toList()) {
                        val matches = new.filter { predicate(a, it) }
                        if (matches.size == 1 && old.count { predicate(it, matches.single()) } == 1) {
                            pair(a, matches.single())
                            progress = true
                        }
                    }
                } while (progress)
            }
            unique { a, b -> a.source.fingerprint == b.fingerprint && a.source.captures == b.captures }
            if (old.size == 1 && new.size == 1) pair(old.single(), new.single())
            // Unchanged duplicate bodies are safe only if the entire group retains the exact compiler identity/body.
            if (old.isNotEmpty() && new.isNotEmpty() && old.size == new.size && old.all { a ->
                    new.any { b -> a.source.target == b.target && a.source.fingerprint == b.fingerprint &&
                        a.source.captures == b.captures }
                }) {
                old.toList().forEach { a -> pair(a, new.single { it.target == a.source.target }) }
            }
            check(old.isEmpty() || new.isEmpty()) {
                "Ambiguous lambda identity in $owner $scope; refusing ordinal-based callback reassignment"
            }
            new.forEach { source -> assigned[source.target] = Triple(source, scope, nextId++) }
        }
    }
    return assigned.values.toList() to nextId
}
