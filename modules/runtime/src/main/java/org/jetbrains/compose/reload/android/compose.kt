package org.jetbrains.compose.reload.android

import android.util.Log
import java.lang.reflect.InvocationTargetException

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
