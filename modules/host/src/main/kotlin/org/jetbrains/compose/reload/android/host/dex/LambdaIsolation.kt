package org.jetbrains.compose.reload.android.host.dex

import org.jetbrains.compose.reload.analysis.ClassId
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/** A closure's installed ABI never changes. Missing captures are supplied with JVM default values. */
internal data class LambdaEntry(
    val name: String,
    val descriptor: String,
    val lambdaId: Int?,
    val captures: List<Capture>,
    val ordinaryTarget: Handle? = null,
    val instance: Boolean = false,
    val access: Int? = null,
) {
    val methodDescriptor: String get() = if (!instance) descriptor else Type.getMethodDescriptor(
        Type.getReturnType(descriptor), *Type.getArgumentTypes(descriptor).drop(1).toTypedArray())
}

/** Session-owned emitted ABI inventory, separate from the original bytes used by upstream analysis. */
internal data class LambdaClassState(
    val versions: List<LambdaVersion>,
    val entries: List<LambdaEntry>,
    val nextId: Int,
)

internal data class LambdaPlan(val classes: Map<ClassId, ByteArray>, val state: LambdaClassState)

/**
 * Keep every installed closure entry, not just the latest D8 pass. Compiler numbered implementation methods
 * are replaced by logical bodies; old entries load their original named/typed captures into the latest body.
 * This neither reconstructs capture initializers nor changes/re-registers a closure object.
 *
 * Prepare is pure with respect to the session. Commit state only when the device applied this owner.
 */
internal fun prepareLambdas(
    classId: ClassId, original: ByteArray, transformed: ByteArray, generation: Int,
    previous: LambdaClassState?, installed: ByteArray?, installedAccessors: List<LambdaAccessor>,
): LambdaPlan {
    val node = readClass(transformed)
    check(node.methods.none { it.name.startsWith(LAMBDA_PREFIX) }) { "Reserved lambda method prefix in ${node.name}" }
    val before = previous ?: installed?.let { seedLambdas(classId, it, installedAccessors) }
        ?: LambdaClassState(emptyList(), emptyList(), 0)
    val sourceNode = readClass(original)
    if (before.versions.isEmpty() && before.entries.isEmpty() &&
        sourceNode.lambdaSites().isEmpty() && node.lambdaSites().isEmpty()) {
        return LambdaPlan(mapOf(classId to transformed), before)
    }
    val sources = sourceLambdas(sourceNode)
    val (matches, nextId) = matchLambdas(node.name, sources, before.versions, before.nextId)
    val entries = before.entries.toMutableList()
    val isInterface = node.access and Opcodes.ACC_INTERFACE != 0
    fun entryFor(source: SourceLambda, id: Int): LambdaEntry {
        val descriptor = source.type.descriptor
        return entries.firstOrNull { it.lambdaId == id && it.name.startsWith(LAMBDA_PREFIX) &&
            it.descriptor == descriptor && it.captures == source.captures }
            ?: LambdaEntry("$LAMBDA_PREFIX$id\$entry\$${entries.size}", descriptor, id, source.captures)
                .also(entries::add)
    }
    val handles = matches.associate { (source, _, id) ->
        val entry = entryFor(source, id)
        source.target to Handle(Opcodes.H_INVOKESTATIC, node.name, entry.name, entry.descriptor, isInterface)
    }
    val versions = before.versions.associateByTo(linkedMapOf()) { it.id }
    versions.replaceAll { _, version -> version.copy(active = false) }
    matches.forEach { (source, scope, id) ->
        val body = copyMethod(node.methods.single { it.key() == source.target.key() }).apply {
            name = "$LAMBDA_PREFIX$id\$body"
            desc = source.type.descriptor
            access = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
            signature = null
            parameters = null
            visibleParameterAnnotations = null
            invisibleParameterAnnotations = null
            visibleTypeAnnotations = null
            invisibleTypeAnnotations = null
            // Instance lambda bodies already use local 0 as receiver; making that parameter explicit preserves slots.
        }
        versions[id] = LambdaVersion(id, scope, source, body)
    }
    // Only current compiler implementation methods are renamed. Ordinary private method references retain their API.
    node.methods.removeAll { method -> sources.any { it.target.key() == method.key() } }
    val bodies = matches.map { versions.getValue(it.third).body }
    rewriteImplementations(node.methods + bodies, handles)

    fun ordinaryEntry(target: Handle): Handle {
        val entry = entries.firstOrNull { it.ordinaryTarget == target }
            ?: LambdaEntry("${LAMBDA_PREFIX}method\$${entries.size}", target.invocationType().descriptor,
                null, emptyList(), target).also(entries::add)
        return Handle(Opcodes.H_INVOKESTATIC, node.name, entry.name, entry.descriptor, isInterface)
    }
    // Bridge original-owner/special handles before moving call sites to a sibling helper class.
    (node.methods + bodies).forEach { method ->
        method.instructions.filterIsInstance<InvokeDynamicInsnNode>()
            .filter { it.bsm.owner == LAMBDA_METAFACTORY }.forEach { site ->
                val target = site.implementation()
                if (!target.name.startsWith(LAMBDA_PREFIX) &&
                    (target.owner == node.name || target.tag == Opcodes.H_INVOKESPECIAL)) {
                    site.bsmArgs[1] = ordinaryEntry(target)
                }
            }
    }
    node.methods.addAll(versions.values.map { copyMethod(it.body) })
    entries.forEach { entry ->
        check(node.methods.none { it.name == entry.name && it.desc == entry.methodDescriptor }) {
            "Lambda entry conflicts with application method ${node.name}.${entry.name}"
        }
        node.methods.add(entryMethod(node.name, isInterface, entry, versions))
    }
    val state = LambdaClassState(versions.values.toList(), entries.toList(), nextId)
    return LambdaPlan(isolateFactories(classId, node, generation), state)
}

/** Discover the exact initial D8 bridges before the first owner replacement. */
private fun seedLambdas(classId: ClassId, code: ByteArray, accessors: List<LambdaAccessor>): LambdaClassState {
    val source = readClass(code)
    val sites = sourceLambdas(source)
    val (matches, _) = matchLambdas(source.name, sites, emptyList(), 0)
    val byTarget = matches.associateBy { it.first.target }
    val entries = accessors.map { accessor ->
        val target = source.lambdaSites().map { it.second.implementation() }.firstOrNull {
            it.owner == accessor.target.owner && it.name == accessor.target.name && it.desc == accessor.target.descriptor
        } ?: accessor.target.handle(accessor.target.owner == source.name && source.access and Opcodes.ACC_INTERFACE != 0)
        val match = byTarget[target]
        LambdaEntry(accessor.name, accessor.descriptor, match?.third,
            match?.first?.captures.orEmpty(), target.takeIf { match == null })
    }
    // Build the baseline bodies and stable entries with the same machinery, then add installed D8 aliases.
    val baseline = prepareLambdas(classId, code, code, 0,
        LambdaClassState(emptyList(), emptyList(), 0), null, emptyList()).state
    // Public compiler bodies (e.g. older Java interface output) may be invoked directly by D8 closures.
    // Retain their original invocation ABI too, including an implicit receiver for instance bodies.
    val direct = matches.map { (site, _, id) ->
        LambdaEntry(site.target.name, site.type.descriptor, id, site.captures,
            instance = site.target.tag != Opcodes.H_INVOKESTATIC, access = site.method.access)
    }
    val byId = baseline.versions.associateBy { it.id }
    check(matches.all { (_, _, id) -> id in byId })
    return baseline.copy(entries = (baseline.entries + entries + direct).distinctBy { MethodKey(it.name, it.methodDescriptor) })
}

private fun LambdaAccessor.Target.handle(ownerIsInterface: Boolean) = Handle(when (kind) {
    LambdaAccessor.Kind.Static -> Opcodes.H_INVOKESTATIC
    LambdaAccessor.Kind.Direct -> if (name == "<init>") Opcodes.H_NEWINVOKESPECIAL else Opcodes.H_INVOKESPECIAL
    LambdaAccessor.Kind.Virtual -> Opcodes.H_INVOKEVIRTUAL
    LambdaAccessor.Kind.Interface -> Opcodes.H_INVOKEINTERFACE
}, owner, name, descriptor, ownerIsInterface || kind == LambdaAccessor.Kind.Interface)

private fun rewriteImplementations(methods: List<MethodNode>, replacements: Map<Handle, Handle>) {
    methods.forEach { method -> method.instructions.forEach { instruction ->
        when (instruction) {
            is InvokeDynamicInsnNode -> instruction.bsmArgs = instruction.bsmArgs.map { argument ->
                if (argument is Handle) replacements[argument] ?: argument else argument
            }.toTypedArray()
            is MethodInsnNode -> replacements.entries.firstOrNull { (before, _) ->
                before.owner == instruction.owner && before.name == instruction.name && before.desc == instruction.desc
            }?.value?.let { after ->
                instruction.opcode = Opcodes.INVOKESTATIC
                instruction.owner = after.owner
                instruction.name = after.name
                instruction.desc = after.desc
                instruction.itf = after.isInterface
            }
        }
    } }
}

private fun entryMethod(
    owner: String, isInterface: Boolean, entry: LambdaEntry, versions: Map<Int, LambdaVersion>,
): MethodNode {
    val access = entry.access ?: (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC or
        (if (entry.instance) 0 else Opcodes.ACC_STATIC))
    val method = MethodNode(access, entry.name, entry.methodDescriptor, null, null)
    val originalTypes = Type.getArgumentTypes(entry.descriptor)
    val slots = originalTypes.mapIndexed { index, _ -> originalTypes.take(index).sumOf { it.size } }
    fun load(index: Int) { method.instructions.add(VarInsnNode(originalTypes[index].getOpcode(Opcodes.ILOAD), slots[index])) }
    val version = entry.lambdaId?.let(versions::getValue)
    if (version == null) {
        val target = checkNotNull(entry.ordinaryTarget)
        if (target.tag == Opcodes.H_NEWINVOKESPECIAL) {
            method.instructions.add(TypeInsnNode(Opcodes.NEW, target.owner))
            method.instructions.add(InsnNode(Opcodes.DUP))
        }
        originalTypes.indices.forEach(::load)
        method.instructions.add(MethodInsnNode(target.invokeOpcode(), target.owner, target.name, target.desc, target.isInterface))
        method.instructions.add(InsnNode(Type.getReturnType(entry.descriptor).getOpcode(Opcodes.IRETURN)))
        return method
    }
    val current = version.source
    current.captures.forEachIndexed { index, capture ->
        val retained = if (capture.name == null) index.takeIf { current.captures == entry.captures }
            else entry.captures.indexOf(capture).takeIf { it >= 0 }
        if (retained != null) load(retained) else defaultValue(method, Type.getType(capture.descriptor))
    }
    val currentTypes = current.type.argumentTypes
    val originalSam = originalTypes.drop(entry.captures.size)
    val currentSam = currentTypes.drop(current.captures.size)
    check(originalSam.size == currentSam.size) { "SAM arity changed for retained lambda $owner/${version.id}" }
    currentSam.forEachIndexed { index, type ->
        load(entry.captures.size + index)
        adaptReference(method, originalSam[index], type)
    }
    method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, version.body.name, version.body.desc, isInterface))
    val result = Type.getReturnType(entry.descriptor)
    adaptReference(method, current.type.returnType, result)
    method.instructions.add(InsnNode(result.getOpcode(Opcodes.IRETURN)))
    return method
}

private fun adaptReference(method: MethodNode, from: Type, to: Type) {
    if (from == to) return
    val references = setOf(Type.OBJECT, Type.ARRAY)
    check(from.sort in references && to.sort in references) { "Incompatible retained lambda invocation types: $from -> $to" }
    if (to != Type.getType(Any::class.java)) method.instructions.add(TypeInsnNode(Opcodes.CHECKCAST, to.internalName))
}

private fun defaultValue(method: MethodNode, type: Type) {
    method.instructions.add(InsnNode(when (type.sort) {
        Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL
        Type.LONG -> Opcodes.LCONST_0
        Type.DOUBLE -> Opcodes.DCONST_0
        Type.FLOAT -> Opcodes.FCONST_0
        else -> Opcodes.ICONST_0
    }))
}

private fun Handle.invokeOpcode() = when (tag) {
    Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC
    Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL
    Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE
    else -> Opcodes.INVOKESPECIAL
}

/** Every factory generation owns fresh D8 synthetic classes; retained callbacks still call owner entries. */
private fun isolateFactories(classId: ClassId, node: ClassNode, generation: Int): Map<ClassId, ByteArray> {
    val sites = node.lambdaSites()
    if (sites.isEmpty()) return mapOf(classId to node.bytes())
    val helper = ClassNode().apply {
        version = node.version
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
        name = "${node.name}\$chr\$lambdas\$$generation"
        superName = "java/lang/Object"
        sourceFile = node.sourceFile
    }
    sites.forEachIndexed { index, (method, site) ->
        val factory = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            "lambda\$$index", site.desc, null, null)
        var slot = 0
        Type.getArgumentTypes(site.desc).forEach { type ->
            factory.instructions.add(VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot))
            slot += type.size
        }
        factory.instructions.add(InvokeDynamicInsnNode(site.name, site.desc, site.bsm, *site.bsmArgs))
        factory.instructions.add(InsnNode(Type.getReturnType(site.desc).getOpcode(Opcodes.IRETURN)))
        helper.methods.add(factory)
        method.instructions.set(site, MethodInsnNode(Opcodes.INVOKESTATIC, helper.name, factory.name, factory.desc, false))
    }
    return mapOf(classId to node.bytes(), ClassId(helper.name) to helper.bytes())
}

private fun ClassNode.bytes(): ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).also { accept(it) }.toByteArray()
