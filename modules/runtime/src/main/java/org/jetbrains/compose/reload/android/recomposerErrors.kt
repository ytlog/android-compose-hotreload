package org.jetbrains.compose.reload.android

import android.util.Log
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.RecomposerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/* Port of upstream 'hot-reload-runtime-jvm/recomposerErrors.kt' */

/** Whether the `RecomposerInfo.errorState` tooling API (introduced in Compose 1.11) is present. */
internal val isRecomposerErrorStateApiAvailable: Boolean by lazy {
    try {
        RecomposerInfo::class.java.getMethod("getErrorState")
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * Emits the most recent composition error captured by any running [Recomposer], or `null` while no
 * recomposer is currently in an error state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun recomposerErrors(): Flow<Throwable?> =
    Recomposer.runningRecomposers.flatMapLatest { recomposers ->
        val errorStates = recomposers.mapNotNull { recomposer -> recomposer.errorStateOrNull() }
        if (errorStates.isEmpty()) flowOf(null)
        else combine(errorStates) { errors -> errors.firstNotNullOfOrNull { it } }
    }

private fun RecomposerInfo.errorStateOrNull(): Flow<Throwable?>? {
    val getErrorState = try {
        javaClass.getMethod("getErrorState").apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        return null
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to access 'RecomposerInfo.errorState'", t)
        return null
    }

    val errorState = try {
        @Suppress("UNCHECKED_CAST")
        getErrorState.invoke(this) as StateFlow<Any?>
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to read 'RecomposerInfo.errorState'", t)
        return null
    }

    return errorState.map { errorInformation -> errorInformation?.causeOrNull() }
}

private fun Any.causeOrNull(): Throwable? = try {
    javaClass.getMethod("getCause").apply { isAccessible = true }.invoke(this) as? Throwable
} catch (t: Throwable) {
    Log.e(TAG, "Failed to access 'RecomposerErrorInformation.cause'", t)
    null
}
