package org.jetbrains.compose.reload.android

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/*
Port of the upstream agent's 'compose.kt'.
The 'Recomposer.Companion' hot reload functions are internal to the Compose runtime: resolved reflectively by name.
 */

private const val RECOMPOSER = "androidx.compose.runtime.Recomposer"

private fun recomposerCompanion(loader: ClassLoader): Any {
    val recomposerClass = loader.loadClass(RECOMPOSER)
    return recomposerClass.getField("Companion").get(null)!!
}

private fun companionMethod(companion: Any, name: String) =
    companion.javaClass.methods.singleOrNull { it.name.contains(name) }

internal fun enableComposeHotReloadMode(loader: ClassLoader) {
    try {
        val companion = recomposerCompanion(loader)
        companionMethod(companion, "setHotReloadEnabled")?.invoke(companion, true)
        Log.d(TAG, "'setHotReloadEnabled' method found, enabled compose hot reload mode")
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "Failed to enable compose hot reload mode", e)
    }
}

internal fun invalidateGroupsWithKey(loader: ClassLoader, key: Int) {
    val companion = recomposerCompanion(loader)
    companionMethod(companion, "invalidateGroupsWithKey")?.invoke(companion, key)
}

/**
 * invalidateGroupsWithKey bashes the selected slot group, destroying even unchanged keyed children.
 * For unchanged scope trees, use Compose's registration observer and ControlledComposition.invalidateAll
 * instead. Enumeration is synchronous and the temporary observer is always disposed; no app state is copied.
 * Resolve the tooling API by name to keep older application-provided Compose versions loadable.
 */
internal fun recomposeUnchangedGroups(loader: ClassLoader, keys: IntArray) {
    val companion = recomposerCompanion(loader)
    val errors = companionMethod(companion, "getCurrentErrors")?.invoke(companion) as? Collection<*>
    if (!errors.isNullOrEmpty()) {
        // The upstream invalidation path also clears recoverable errors and retries failed compositions.
        // Plain invalidateAll cannot resume a recomposer suspended by a composition exception.
        Log.d(TAG, "Retrying failed compositions through group invalidation")
        keys.forEach { invalidateGroupsWithKey(loader, it) }
        return
    }
    val observerType: Class<*>
    val infoType: Class<*>
    val observe: java.lang.reflect.Method
    try {
        observerType = loader.loadClass("androidx.compose.runtime.tooling.CompositionRegistrationObserver")
        infoType = loader.loadClass("androidx.compose.runtime.RecomposerInfo")
        observe = infoType.getMethod("observe", observerType)
    } catch (missing: ReflectiveOperationException) {
        Log.w(TAG, "Compose registration observer unavailable; using group reset (remember preservation unavailable)")
        keys.forEach { invalidateGroupsWithKey(loader, it) }
        return
    }
    val controlled = loader.loadClass("androidx.compose.runtime.ControlledComposition")
    val compositions = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val observer = Proxy.newProxyInstance(loader, arrayOf(observerType)) { proxy, method, arguments ->
        when (method.name) {
            "onCompositionRegistered" -> { arguments?.firstOrNull()?.let { if (controlled.isInstance(it)) compositions.add(it) }; null }
            "onCompositionUnregistered" -> null
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            "toString" -> "HotReloadCompositionObserver"
            else -> error("Unexpected composition observer method: ${method.name}")
        }
    }
    val flow = checkNotNull(companionMethod(companion, "getRunningRecomposers")).invoke(companion)
    val running = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow) as Iterable<*>
    val dispose = loader.loadClass("androidx.compose.runtime.tooling.CompositionObserverHandle").getMethod("dispose")
    running.forEach { info ->
        val handle = observe.invoke(info, observer)
        dispose.invoke(handle)
    }
    val invalidate = controlled.getMethod("invalidateAll")
    val scopes = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val scopeType: Class<*>
    val force: java.lang.reflect.Method
    try {
        scopeType = loader.loadClass("androidx.compose.runtime.RecomposeScopeImpl")
        force = scopeType.getMethod("setForcedRecompose", Boolean::class.javaPrimitiveType)
        val composerType = loader.loadClass("androidx.compose.runtime.Composer")
        val compositionData = composerType.getMethod("getCompositionData")
        val dataType = loader.loadClass("androidx.compose.runtime.tooling.CompositionData")
        val groups = dataType.getMethod("getCompositionGroups")
        val groupData = loader.loadClass("androidx.compose.runtime.tooling.CompositionGroup").getMethod("getData")
        val pending = java.util.ArrayDeque<Any>()
        compositions.forEach { composition ->
            val getComposer = companionMethod(composition, "getComposer")
                ?: throw NoSuchMethodException("Composition composer accessor unavailable")
            pending.add(compositionData.invoke(getComposer.invoke(composition)))
        }
        while (pending.isNotEmpty()) {
            (groups.invoke(pending.removeFirst()) as Iterable<*>).forEach { group ->
                if (group == null) return@forEach
                (groupData.invoke(group) as Iterable<*>).forEach { value ->
                    if (value != null && scopeType.isInstance(value)) scopes.add(value)
                }
                pending.add(group)
            }
        }
    } catch (missing: ReflectiveOperationException) {
        Log.w(TAG, "Compose scope forcing unavailable; using group reset (remember preservation unavailable)", missing)
        keys.forEach { invalidateGroupsWithKey(loader, it) }
        return
    }
    // A parent invalidation can subsume its child invalidations. Without this one-shot flag,
    // unchanged child arguments may still skip the newly installed stateless rendering body.
    scopes.forEach { force.invoke(it, true) }
    compositions.forEach { invalidate.invoke(it) }
    Log.d(TAG, "Forced ${scopes.size} scopes in ${compositions.size} compositions without resetting groups: ${keys.joinToString()}")
}

/**
 * Forces a full composition teardown and rebuild ("hard" reload).
 * Compose's 'saveStateAndDisposeForHotReload' followed by 'loadStateAndComposeForHotReload'.
 */
internal fun reloadCompositionState(loader: ClassLoader) = runOnUiThreadBlocking {
    val companion = recomposerCompanion(loader)
    val save = companionMethod(companion, "saveStateAndDisposeForHotReload")
    val load = companionMethod(companion, "loadStateAndComposeForHotReload")

    if (save == null || load == null) {
        Log.w(TAG, "'saveStateAndDisposeForHotReload'/'loadStateAndComposeForHotReload' not found")
        return@runOnUiThreadBlocking
    }

    val token = try {
        save.invoke(companion)
    } catch (t: InvocationTargetException) {
        Log.w(TAG, "'saveStateAndDisposeForHotReload' failed", t.cause ?: t)
        return@runOnUiThreadBlocking
    }

    try {
        load.invoke(companion, token)
    } catch (t: InvocationTargetException) {
        Log.w(TAG, "'loadStateAndComposeForHotReload' completed with a trailing error", t.cause ?: t)
    }
}
