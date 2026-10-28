package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ApplicationInfo
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.FieldId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.analysis.ScopeInfo
import org.objectweb.asm.Type

/** Follow execution, not the upstream invalidation graph, through remember's factory calls. */
internal class CacheEvaluation(
    private val current: ApplicationInfo,
    private val pending: ApplicationInfo,
    private val dirtyMethods: Set<MethodId>,
    private val dirtyFields: Set<FieldId>,
    private val declarations: (ClassId) -> Set<MethodId>? = { null },
    private val typesMayOverlap: (ClassId, ClassId) -> Boolean = { _, _ -> true },
) {
    private val after = current.classIndex + pending.classIndex
    private val methods = after.values.flatMap { it.methods.entries }.associate { it.toPair() }
    private val allMethods = current.methodIndex.values + methods.values
    private val deferred = allMethods.flatMap { it.rootScope.extras[MethodExecutionCode]?.deferred.orEmpty() }
        .groupBy({ it.invocation }, { it.call })
    private val deferredByType = deferred.entries.groupBy({ it.key.classId }, { it.value })
        .mapValues { (_, calls) -> calls.flatten() }
    private val unchanged = hashSetOf<ExecutedCall>()

    private fun shape(scope: ScopeInfo): Any = listOf(scope.scopeHash, scope.group, scope.children.map(::shape))

    private fun types(owner: ClassId) =
        setOf(owner) + current.superIndexInverse[owner].orEmpty() + pending.superIndexInverse[owner].orEmpty()

    fun changedInitializer(type: ClassId): Boolean = changed(ExecutedCall(MethodId(type, "<clinit>", "()V"), false))

    private fun acceptsArguments(invocation: ExecutedCall, implementation: ExecutedCall): Boolean {
        val arity = Type.getArgumentTypes(invocation.method.methodDescriptor).size
        val parameters = Type.getArgumentTypes(implementation.method.methodDescriptor).toList()
        val actual = if (implementation.virtual && parameters.size < arity)
            listOf(Type.getObjectType(implementation.method.classId.value)) + parameters else parameters
        if (actual.size < arity) return true
        val expected = actual.takeLast(arity)
        return invocation.argumentTypes.all { (index, type) ->
            val parameter = expected.getOrNull(index) ?: return@all true
            val boxed = when (parameter.sort) {
                Type.OBJECT -> parameter.internalName
                Type.BOOLEAN -> "java/lang/Boolean"
                Type.BYTE -> "java/lang/Byte"
                Type.CHAR -> "java/lang/Character"
                Type.SHORT -> "java/lang/Short"
                Type.INT -> "java/lang/Integer"
                Type.FLOAT -> "java/lang/Float"
                Type.LONG -> "java/lang/Long"
                Type.DOUBLE -> "java/lang/Double"
                else -> return@all true
            }
            typesMayOverlap(type, ClassId(boxed))
        }
    }

    fun changed(start: ExecutedCall): Boolean = changePath(start) != null

    private fun bind(call: ExecutedCall, incoming: ExecutedCall): ExecutedCall {
        fun type(parameter: Int) = if (parameter == -1) incoming.receiverType else incoming.argumentTypes[parameter]
        fun callbacks(parameter: Int) = if (parameter == -1) incoming.receiverCallbacks else incoming.argumentCallbacks[parameter]
        val types = call.argumentTypes.toMutableMap()
        val closures = call.argumentCallbacks.toMutableMap()
        val nulls = call.nullArguments.toMutableSet()
        call.parameterArguments.forEach { (argument, parameter) ->
            type(parameter)?.let { types[argument] = it }
            callbacks(parameter)?.let { closures[argument] = it }
            if (parameter in incoming.nullArguments) nulls += argument
        }
        return call.copy(argumentTypes = types, argumentCallbacks = closures, nullArguments = nulls,
            receiverType = call.receiverParameter?.let(::type) ?: call.receiverType,
            receiverCallbacks = call.receiverParameter?.let(::callbacks) ?: call.receiverCallbacks,
            parameterArguments = emptyMap(), receiverParameter = null)
    }

    private fun bindSam(invocation: ExecutedCall, implementation: ExecutedCall): ExecutedCall {
        val arity = Type.getArgumentTypes(invocation.method.methodDescriptor).size
        val offset = Type.getArgumentTypes(implementation.method.methodDescriptor).size - arity
        if (offset < -1 || (offset == -1 && !implementation.virtual)) return implementation
        return implementation.copy(
            argumentTypes = implementation.argumentTypes + invocation.argumentTypes.entries
                .filter { it.key + offset >= 0 }.associate { it.key + offset to it.value },
            argumentCallbacks = implementation.argumentCallbacks + invocation.argumentCallbacks.entries
                .filter { it.key + offset >= 0 }.associate { it.key + offset to it.value },
            nullArguments = implementation.nullArguments + invocation.nullArguments.map { it + offset }.filter { it >= 0 },
            receiverType = if (offset == -1) invocation.argumentTypes[0] else implementation.receiverType,
            receiverCallbacks = if (offset == -1) invocation.argumentCallbacks[0] else implementation.receiverCallbacks,
        )
    }

    /** Keep the executed path available for diagnosis, including opaque-call expansion. */
    fun changePath(start: ExecutedCall): List<MethodId>? {
        val expanded = hashMapOf<ExecutedCall, ExecutedCall?>()
        val visited = hashSetOf<ExecutedCall>()
        val queue = java.util.ArrayDeque<ExecutedCall>()
        var parent: ExecutedCall? = null
        fun enqueue(call: ExecutedCall, caller: ExecutedCall? = parent) {
            if (call in expanded || call in unchanged) return
            expanded[call] = caller
            queue.add(call)
            if (call.virtual) {
                if (call.receiverCallbacks != null) {
                    call.receiverCallbacks.filter { it.invocation.methodName == call.method.methodName &&
                        it.invocation.methodDescriptor == call.method.methodDescriptor }
                        .forEach { enqueue(bindSam(call, it.call), call) }
                    return
                }
                val owner = call.receiverType ?: call.method.classId
                types(owner).forEach { type ->
                    val target = MethodId(type, call.method.methodName, call.method.methodDescriptor)
                    val casts = methods[target]?.rootScope?.extras[MethodExecutionCode]?.bridgeCasts.orEmpty()
                    val previousCasts = current.methodIndex[target]?.rootScope?.extras[MethodExecutionCode]?.bridgeCasts.orEmpty()
                    if (casts.any { (index, expected) -> previousCasts[index] == expected &&
                        call.argumentTypes[index]?.let { !typesMayOverlap(it, expected) } == true }) return@forEach
                    enqueue(call.copy(method = target, virtual = false), call)
                    // Object.toString/hashCode on a function object does not invoke its SAM body.
                    deferred[target].orEmpty().filter { acceptsArguments(call, it) }
                        .forEach { enqueue(bindSam(call, it), call) }
                }
            }
        }
        enqueue(start)
        while (queue.isNotEmpty()) {
            val call = queue.removeFirst()
            parent = call
            val id = call.method
            fun path(): List<MethodId> = generateSequence(call) { expanded[it] }.map { it.method }.toList().asReversed()
            val before = current.methodIndex[id]
            val next = methods[id]
            if (before == null && next == null) {
                // A known lazy factory stores its callback. A later read/collect remains an
                // execution dependency; an unknown library still may invoke typed arguments.
                if (DeferredLibraryCalls.defers(id)) continue
                val arguments = Type.getArgumentTypes(id.methodDescriptor)
                // A foreign consumer may call methods on the supplied object. Additional
                // arguments it creates for those methods are not arbitrary app-owned callbacks.
                val unavailable = call.nullArguments + if (call.externalArguments) arguments.indices.toSet() else emptySet()
                DeferredLibraryCalls.evaluatedTypes(id, unavailable).forEach { type ->
                    val positions = arguments.indices.filter {
                        it !in call.nullArguments && arguments[it].descriptor == "L${type.value};"
                    }
                    positions.mapNotNull { call.argumentCallbacks[it] }.flatten().forEach { enqueue(it.call) }
                    val receivers = positions.filter { it !in call.argumentCallbacks }
                        .map { call.argumentTypes[it] ?: type }
                        .let { if (positions.isEmpty()) listOf(type) else it }
                    val signatures = declarations(type)
                    receivers.forEach { receiver ->
                        if (signatures != null) {
                            // An opaque consumer of Closeable can dispatch close(), not private
                            // helper methods with new callback arguments on every implementation.
                            signatures.forEach { enqueue(ExecutedCall(it, true, receiver, externalArguments = true)) }
                        } else {
                            // Missing declarations retain the old conservative bound.
                            types(receiver).forEach { candidate ->
                                deferredByType[candidate].orEmpty().forEach { enqueue(it) }
                                (current.classIndex[candidate]?.methods.orEmpty().keys + after[candidate]?.methods.orEmpty().keys)
                                    .filter { it.methodName != "<init>" && it.methodName != "<clinit>" }
                                    .forEach { enqueue(ExecutedCall(it, false)) }
                            }
                        }
                    }
                }
                continue
            }
            if (!visited.add(call)) continue
            if (before == null || next == null || shape(before.rootScope) != shape(next.rootScope)) return path()
            for (scope in listOf(before.rootScope, next.rootScope)) {
                val code = scope.extras[MethodExecutionCode]
                if (code == null || !code.known) {
                    if (id in dirtyMethods) return path()
                    continue
                }
                if (code.fields.any { it in dirtyFields }) return path()
                code.initializers.forEach { enqueue(ExecutedCall(MethodId(it, "<clinit>", "()V"), false)) }
                // Real interface/virtual invocations may execute an overriding body or an indy lambda.
                code.calls.forEach { enqueue(bind(it, call)) }
            }
        }
        unchanged += expanded.keys
        return null
    }
}
