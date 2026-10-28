package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ApplicationInfo
import org.jetbrains.compose.reload.analysis.ComposeGroupKey
import org.jetbrains.compose.reload.analysis.FieldId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.analysis.ScopeHash
import org.jetbrains.compose.reload.analysis.ScopeInfo
import org.jetbrains.compose.reload.analysis.ScopeType
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.info

private val logger = createLogger()

/** A changed dependency requires recomposition, not destruction of an unchanged caller's slots. */
internal class ComposeInvalidation(
    val reset: IntArray,
    val recompose: IntArray,
)

internal fun composeInvalidation(
    current: ApplicationInfo, pending: ApplicationInfo, dirtyScopes: List<ScopeInfo>,
    declarations: (ClassId) -> Set<MethodId>? = { null },
    typesMayOverlap: (ClassId, ClassId) -> Boolean = { _, _ -> true },
    compatibleConstructors: Set<MethodId> = emptySet(),
): ComposeInvalidation {
    val reset = mutableListOf<Int>()
    val recompose = mutableListOf<Int>()
    val dirtyMethods = dirtyScopes.mapTo(HashSet()) { it.methodId }
    val fields = pending.classIndex.keys.flatMap { id ->
        current.classIndex[id]?.fields.orEmpty().keys + pending.classIndex[id]?.fields.orEmpty().keys
    }
    val dirtyFields = fields.filterTo(HashSet()) { id ->
        current.fieldIndex[id]?.let { before ->
            pending.fieldIndex[id]?.let { after ->
                before.initialValue != after.initialValue ||
                    before.additionalChangeIndicatorHash != after.additionalChangeIndicatorHash
            } ?: true
        } ?: true
    }
    val evaluation = CacheEvaluation(current, pending, dirtyMethods, dirtyFields, declarations, typesMayOverlap, compatibleConstructors)
    fun cacheReason(scope: ScopeInfo, recursive: Boolean = true): String? {
        val code = scope.extras[ComposeScopeCode] ?: return "missing scope metadata: ${scope.methodId}"
        if (!code.parsed) return "missing cache ranges: ${scope.methodId} (${scope.group})"
        if (code.hasCache) {
            code.directCalls.firstOrNull(evaluation::changed)?.let { return "cache evaluates changed execution: $it" }
            code.initializers.firstOrNull(evaluation::changedInitializer)?.let { return "cache evaluates changed class initializer: $it" }
            code.fields.firstOrNull { it in dirtyFields }?.let { return "cache reads dirty field: $it" }
        }
        return if (recursive) scope.children.firstNotNullOfOrNull { cacheReason(it) } else null
    }
    fun hasCache(scope: ScopeInfo): Boolean = scope.extras[ComposeScopeCode]?.let {
        !it.parsed || it.hasCache || scope.children.any(::hasCache)
    } ?: true
    dirtyScopes.mapNotNull { it.group }.distinct().forEach { group ->
        val before = current.groupIndex[group].orEmpty()
        val after = before.filter { it.methodId.classId !in pending.classIndex } + pending.groupIndex[group].orEmpty()
        // Preserve unchanged trees and constant edits in stateless trees with identical calls/groups.
        // Changed cache initializers, their evaluated dependencies, missing metadata and changed layouts reset.
        val unchanged = before.map(::scopeShape).toSet() == after.map(::scopeShape).toSet()
        val stateless = (before + after).none(::hasCache) &&
            before.map(::scopeLayout).toSet() == after.map(::scopeLayout).toSet()
        val cache = (before + after).firstNotNullOfOrNull { cacheReason(it) }
        if (before.isNotEmpty() && after.isNotEmpty() && (unchanged || stateless) &&
            cache == null) {
            recompose += group.key
        } else {
            logger.info("Resetting Compose group ${group.key}: unchanged=$unchanged, stateless=$stateless, reason=${cache ?: "changed scope layout"}")
            reset += group.key
        }
    }
    // Reset changed child groups, not an unchanged parent which happens to contain them. This is
    // bounded by exact parent instructions, complete cache metadata and distinct real group keys.
    // A changed direct cache/slot sequence, ambiguous group or cross-boundary branch still resets.
    val preserved = mutableSetOf<Int>()
    val toInspect = java.util.ArrayDeque(reset.distinct())
    val inspected = mutableSetOf<Int>()
    inspect@ while (toInspect.isNotEmpty()) {
        val key = toInspect.removeFirst()
        if (!inspected.add(key)) continue@inspect
        val group = ComposeGroupKey(key)
        val beforeChain = groupBoundary(current.groupIndex[group].orEmpty()) ?: continue@inspect
        val candidates = current.groupIndex[group].orEmpty().filter { it.methodId.classId !in pending.classIndex } +
            pending.groupIndex[group].orEmpty()
        val afterChain = groupBoundary(candidates) ?: continue@inspect
        if (beforeChain.size != afterChain.size || beforeChain.zip(afterChain).any { (old, new) ->
                old.methodId != new.methodId || old.scopeType != new.scopeType ||
                    old.extras[ComposeScopeCode]?.ownCode == null ||
                    old.extras[ComposeScopeCode]?.ownCode != new.extras[ComposeScopeCode]?.ownCode ||
                    cacheReason(old, false) != null || cacheReason(new, false) != null
            }) continue@inspect
        val before = beforeChain.last(); val after = afterChain.last()
        if (before.children.size != after.children.size) continue@inspect
        val children = before.children + after.children
        if (children.any { it.group == null || it.group!!.key == key || it.group!!.key in compilerRememberKeys ||
                it.scopeType !in setOf(ScopeType.RestartGroup, ScopeType.ReplaceGroup) ||
                it.extras[ComposeScopeCode]?.parsed != true } ||
            before.children.map { it.group }.distinct().size != before.children.size ||
            after.children.map { it.group }.distinct().size != after.children.size) continue@inspect
        if (before.children.zip(after.children).any { (old, new) -> old.scopeType != new.scopeType }) continue@inspect
        before.children.zip(after.children).forEach { (old, new) ->
            if (scopeShape(old) != scopeShape(new) || cacheReason(old) != null || cacheReason(new) != null) {
                listOf(old.group!!.key, new.group!!.key).forEach { childKey ->
                    reset += childKey; toInspect.add(childKey)
                }
            }
        }
        preserved += key; recompose += key
    }
    // A parent may nominate a child before or after that child's own unchanged boundary is proven.
    // Apply preservation after all plans so debug-only child changes do not depend on traversal order.
    val finalReset = reset.distinct().filter { it !in preserved }
    return ComposeInvalidation(finalReset.toIntArray(), recompose.distinct().filter { it !in finalReset }.toIntArray())
}

private data class ScopeShape(
    val method: MethodId,
    val type: ScopeType,
    val hash: ScopeHash,
    val group: ComposeGroupKey?,
    val children: List<ScopeShape>,
)

private fun scopeShape(scope: ScopeInfo): ScopeShape = ScopeShape(
    scope.methodId, scope.scopeType, scope.scopeHash, scope.group, scope.children.map(::scopeShape),
)

private data class ScopeLayout(
    val method: MethodId,
    val type: ScopeType,
    val group: ComposeGroupKey?,
    val calls: List<MethodId>,
    val fields: List<FieldId>,
    val children: List<ScopeLayout>,
)

private fun scopeLayout(scope: ScopeInfo): ScopeLayout = ScopeLayout(
    scope.methodId, scope.scopeType, scope.group, scope.methodDependenciesList,
    scope.fieldDependenciesList, scope.children.map(::scopeLayout),
)

// Non-unique remember overload groups from the pinned upstream ComposeGroupKey contract.
private val compilerRememberKeys = setOf(1849434622, 5004770, -1633490746, -1746271574, -1224400529)


/** FunctionKeyMeta describes the same runtime group as its sole restart child, not a collision. */
private fun groupBoundary(scopes: Collection<ScopeInfo>): List<ScopeInfo>? {
    if (scopes.size == 1) return scopes.toList()
    if (scopes.size != 2) return null
    val method = scopes.singleOrNull { it.scopeType == ScopeType.Method } ?: return null
    val child = method.children.singleOrNull() ?: return null
    if (child !in scopes || child.scopeType != ScopeType.RestartGroup || child.methodId != method.methodId ||
        child.group != method.group) return null
    return listOf(method, child)
}
