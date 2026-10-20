@file:JvmName("AndroidDevelopmentEntryPoint")

package org.jetbrains.compose.reload.android

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import org.jetbrains.compose.reload.android.protocol.Protocol
import kotlinx.coroutines.flow.update

/**
 * Port of upstream 'hot-reload-runtime-jvm/DevelopmentEntryPoint.kt' (Android has no windows: no 'WindowId').
 * Installed by instrumenting 'androidx.activity.compose.setContent' (see the Gradle plugin),
 * as upstream instruments Compose Desktop's 'Window'.
 */
@Composable
internal fun DevelopmentEntryPoint(child: @Composable () -> Unit) {
    /* Checking if we're currently in the stack of a hot reload */
    if (hotReloadStateLocal.current != null) {
        Log.w(TAG, "Skipping 'DevelopmentEntryPoint': We're already in an entry point")
        child()
        return
    }

    // CMP-10248: use error state API to detect UI errors for Compose 1.11+.
    // Use runCatching() workaround below for older Compose.
    if (isRecomposerErrorStateApiAvailable) LaunchedEffect(Unit) {
        var reported: Throwable? = null
        recomposerErrors().collect { error ->
            when {
                error != null && error !== reported -> {
                    Log.e(TAG, "UI exception:", error)
                    reported = error
                    publishUIError(error)
                }

                error == null && reported != null -> reported = null
            }
        }
    }

    val currentHotReloadState by hotReloadState.collectAsState()

    val intercepted: @Composable () -> Unit = {
        @Suppress("ILLEGAL_RUN_CATCHING_AROUND_COMPOSABLE")
        runCatching {
            child()
        }.onFailure { exception ->
            Log.e(TAG, "Failed invoking 'DevelopmentEntryPoint':", exception)
            if (!isRecomposerErrorStateApiAvailable) publishUIError(exception)
        }.onSuccess {
            AndroidHotReloadRuntime.send(
                Protocol.UIRendered(currentHotReloadState.reloadRequestId, currentHotReloadState.iteration)
            )
        }.getOrThrow()
    }

    CompositionLocalProvider(hotReloadStateLocal provides currentHotReloadState) {
        key(currentHotReloadState.key) {
            intercepted()
        }
    }
}

private fun publishUIError(exception: Throwable) {
    AndroidHotReloadRuntime.send(Protocol.UIException(exception.message, Protocol.StackFrame.of(exception)))
}

/**
 * Replaces 'ComposeView.setContent' inside 'androidx.activity.compose.setContent'
 * (upstream: 'JvmDevelopmentEntryPoint.setContent' replaces 'ComposeWindow.setContent').
 */
@Suppress("unused") // used by instrumentation
fun setContent(view: ComposeView, content: @Composable () -> Unit) {
    view.setContent {
        DevelopmentEntryPoint(content)
    }
}

/**
 * Port of upstream 'resetComposition.kt': in response to 'CleanCompositionRequest'/'RetryFailedCompositionRequest'.
 * - hard reset: full teardown+rebuild via Compose's hot-reload state save/load
 * - light reset: bump 'hotReloadState.key', which only disposes and recomposes the entry point's subtree
 */
internal fun resetComposition(loader: ClassLoader, hardReset: Boolean) {
    if (hardReset) {
        reloadCompositionState(loader)
    } else {
        hotReloadState.update { state -> state.copy(key = state.key + 1) }
    }
}
