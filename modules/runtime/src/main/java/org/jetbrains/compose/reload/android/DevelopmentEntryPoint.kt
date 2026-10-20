@file:JvmName("AndroidDevelopmentEntryPoint")

package org.jetbrains.compose.reload.android

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ComposeView
import org.jetbrains.compose.reload.android.protocol.Protocol
import kotlinx.coroutines.flow.update

/** LocalResources was introduced after LocalContext; keep older Compose applications usable. */
@Suppress("UNCHECKED_CAST")
private val resourcesLocal: ProvidableCompositionLocal<android.content.res.Resources>? by lazy {
    runCatching {
        Class.forName("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
            .getMethod("getLocalResources").invoke(null) as ProvidableCompositionLocal<android.content.res.Resources>
    }.getOrNull()
}

@Suppress("UNCHECKED_CAST")
private val imageVectorCacheLocal: ProvidableCompositionLocal<Any>? by lazy {
    runCatching {
        Class.forName("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
            .getMethod("getLocalImageVectorCache").invoke(null) as ProvidableCompositionLocal<Any>
    }.getOrNull()
}

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
    val baseContext = LocalContext.current
    val configuration = LocalConfiguration.current
    val context = remember(baseContext, configuration, currentHotReloadState.resourceVersion) {
        // Leave the normal context in place until the first resource update.
        if (currentHotReloadState.resourceVersion == 0) baseContext
        else resourceContext(baseContext, currentHotReloadState.resourceVersion)
    }

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

    val provided = arrayListOf<ProvidedValue<*>>(
        hotReloadStateLocal provides currentHotReloadState, LocalContext provides context
    )
    // Keep Compose's configuration-dependent default until resources are overridden. Thereafter
    // a configuration change creates a fresh resourceContext above, so its Resources also changes.
    if (currentHotReloadState.resourceVersion != 0) {
        val vectorCache = imageVectorCacheLocal?.current
        remember(vectorCache, currentHotReloadState.resourceVersion) {
            // Cache keys retain Theme and its Resources even when the cached vector is a weak reference.
            // Prune before reading new painters, so old resource archives can be released.
            if (vectorCache != null) runCatching {
                vectorCache.javaClass.getMethod("clear").invoke(vectorCache)
                Log.d(TAG, "Cleared Compose vector resource cache")
            }.onFailure { Log.w(TAG, "Could not clear Compose vector resource cache", it) }
            Unit
        }
        resourcesLocal?.let { provided += it provides context.resources }
    }
    CompositionLocalProvider(*provided.toTypedArray()) {
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
