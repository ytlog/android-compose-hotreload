package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ApplicationInfo
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.FieldId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.analysis.ScopeInfo

/** Follow execution, not the upstream invalidation graph, through remember's factory calls. */
internal class CacheEvaluation(
    private val current: ApplicationInfo,
    private val pending: ApplicationInfo,
    private val dirtyMethods: Set<MethodId>,
    private val dirtyFields: Set<FieldId>,
) {
    private val after = current.classIndex + pending.classIndex
    private val methods = after.values.flatMap { it.methods.entries }.associate { it.toPair() }
    private val allMethods = current.methodIndex.values + methods.values
    private val deferred = allMethods.flatMap { it.rootScope.extras[MethodExecutionCode]?.deferred.orEmpty() }
        .groupBy({ it.type }, { it.call })

    private fun shape(scope: ScopeInfo): Any = listOf(scope.scopeHash, scope.group, scope.children.map(::shape))

    private fun types(owner: ClassId) =
        setOf(owner) + current.superIndexInverse[owner].orEmpty() + pending.superIndexInverse[owner].orEmpty()

    fun changedInitializer(type: ClassId): Boolean = changed(ExecutedCall(MethodId(type, "<clinit>", "()V"), false))

    fun changed(start: ExecutedCall): Boolean {
        val expanded = hashSetOf<ExecutedCall>()
        val visited = hashSetOf<MethodId>()
        val queue = java.util.ArrayDeque<MethodId>()
        fun enqueue(call: ExecutedCall) {
            if (!expanded.add(call)) return
            queue.add(call.method)
            if (call.virtual) {
                val owner = call.method.classId
                types(owner).forEach { type ->
                    queue.add(MethodId(type, call.method.methodName, call.method.methodDescriptor))
                    deferred[type].orEmpty().forEach(::enqueue)
                }
            }
        }
        enqueue(start)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            val before = current.methodIndex[id]
            val next = methods[id]
            if (before == null && next == null) {
                // A known lazy factory stores its callback. A later read/collect remains an
                // execution dependency; an unknown library still may invoke typed arguments.
                if (DeferredLibraryCalls.defers(id)) continue
                DeferredLibraryCalls.evaluatedTypes(id).forEach { type ->
                    types(type).forEach { type ->
                        deferred[type].orEmpty().forEach(::enqueue)
                        (current.classIndex[type]?.methods.orEmpty().keys + after[type]?.methods.orEmpty().keys)
                            .filter { it.methodName != "<init>" && it.methodName != "<clinit>" }
                            .forEach { enqueue(ExecutedCall(it, false)) }
                    }
                }
                continue
            }
            if (before == null || next == null || shape(before.rootScope) != shape(next.rootScope)) return true
            for (scope in listOf(before.rootScope, next.rootScope)) {
                val code = scope.extras[MethodExecutionCode]
                if (code == null || !code.known) {
                    if (id in dirtyMethods) return true
                    continue
                }
                if (code.fields.any { it in dirtyFields }) return true
                code.initializers.forEach { queue.add(MethodId(it, "<clinit>", "()V")) }
                // Real interface/virtual invocations may execute an overriding body or an indy lambda.
                code.calls.forEach(::enqueue)
            }
        }
        return false
    }
}
