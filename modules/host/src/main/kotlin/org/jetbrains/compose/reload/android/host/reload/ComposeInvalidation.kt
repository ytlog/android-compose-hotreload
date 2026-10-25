package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ApplicationInfo
import org.jetbrains.compose.reload.analysis.ComposeGroupKey
import org.jetbrains.compose.reload.analysis.FieldId
import org.jetbrains.compose.reload.analysis.MethodId
import org.jetbrains.compose.reload.analysis.ScopeHash
import org.jetbrains.compose.reload.analysis.ScopeInfo
import org.jetbrains.compose.reload.analysis.ScopeType
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
    val evaluation = CacheEvaluation(current, pending, dirtyMethods, dirtyFields)
    fun cacheReason(scope: ScopeInfo): String? {
        val code = scope.extras[ComposeScopeCode] ?: return "missing scope metadata: ${scope.methodId}"
        if (!code.parsed) return "missing cache ranges: ${scope.methodId} (${scope.group})"
        if (code.hasCache) {
            code.directCalls.firstOrNull(evaluation::changed)?.let { return "cache evaluates changed execution: $it" }
            code.initializers.firstOrNull(evaluation::changedInitializer)?.let { return "cache evaluates changed class initializer: $it" }
            code.fields.firstOrNull { it in dirtyFields }?.let { return "cache reads dirty field: $it" }
        }
        return scope.children.firstNotNullOfOrNull(::cacheReason)
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
        val cache = (before + after).firstNotNullOfOrNull(::cacheReason)
        if (before.isNotEmpty() && after.isNotEmpty() && (unchanged || stateless) &&
            cache == null) {
            recompose += group.key
        } else {
            logger.info("Resetting Compose group ${group.key}: unchanged=$unchanged, stateless=$stateless, reason=${cache ?: "changed scope layout"}")
            reset += group.key
        }
    }
    return ComposeInvalidation(reset.toIntArray(), recompose.toIntArray())
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
