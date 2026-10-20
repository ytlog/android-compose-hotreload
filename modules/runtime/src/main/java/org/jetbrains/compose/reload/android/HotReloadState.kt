package org.jetbrains.compose.reload.android

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Port of upstream 'hot-reload-runtime-jvm/HotReloadState.kt' */
internal data class HotReloadState(
    val reloadRequestId: String? = null,
    val iteration: Int,
    val reloadError: Throwable? = null,
    val key: Int = 0,
)

internal val hotReloadStateLocal = compositionLocalOf<HotReloadState?> { null }

internal val hotReloadState: MutableStateFlow<HotReloadState> = MutableStateFlow(HotReloadState(null, 0))

/** upstream: 'invokeAfterHotReload' listener of 'hotReloadState' */
internal fun onHotReload(reloadRequestId: String, error: Throwable?) {
    hotReloadState.update { state ->
        state.copy(
            reloadRequestId = reloadRequestId,
            iteration = state.iteration + 1,
            reloadError = error,
        )
    }
}
