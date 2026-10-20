package org.jetbrains.compose.reload.android

import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask

/* Port of the upstream agent's 'uiThread.kt': the Android main thread replaces the Swing EDT */

internal val isUiThread: Boolean
    get() = Looper.myLooper() == Looper.getMainLooper()

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

internal fun <T> runOnUiThreadBlocking(action: () -> T): T {
    if (isUiThread) return action()
    val task = FutureTask(action)
    check(mainHandler.post(task)) { "Main thread is not available" }
    return try {
        task.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }
}

internal fun runOnUiThreadAsync(action: () -> Unit) {
    mainHandler.post(action)
}
