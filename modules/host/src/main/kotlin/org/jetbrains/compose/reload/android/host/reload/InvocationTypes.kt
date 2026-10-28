package org.jetbrains.compose.reload.android.host.reload

import javassist.ClassPool
import javassist.Modifier
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MethodId

/** Read API declarations from the app/boot classpath without executing or tracking library bodies. */
internal class InvocationTypes(private val pool: ClassPool) {
    private val methods = HashMap<ClassId, Set<MethodId>?>()
    private val overlap = HashMap<Pair<ClassId, ClassId>, Boolean>()

    fun declarations(type: ClassId): Set<MethodId>? {
        if (type !in methods) methods[type] = runCatching {
            pool.get(type.toFqn()).methods.filter { !Modifier.isStatic(it.modifiers) }
                .mapTo(mutableSetOf()) { MethodId(type, it.name, it.signature) }
        }.getOrNull()
        return methods[type]
    }

    fun mayOverlap(left: ClassId, right: ClassId): Boolean = overlap.getOrPut(left to right) {
        runCatching {
            val a = pool.get(left.toFqn())
            val b = pool.get(right.toFqn())
            a.subtypeOf(b) || b.subtypeOf(a) ||
                (!Modifier.isFinal(a.modifiers) && !Modifier.isFinal(b.modifiers) && (a.isInterface || b.isInterface))
        }.getOrDefault(true)
    }
}
