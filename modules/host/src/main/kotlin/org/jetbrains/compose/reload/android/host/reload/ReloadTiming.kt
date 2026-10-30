package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.info
import java.util.concurrent.TimeUnit

private val timingLogger = createLogger()

/** Monotonic local durations only; never compare host and device clocks or log payload contents. */
internal fun recordReloadTiming(requestId: String, stage: String, started: Long, bytes: Long? = null, success: Boolean = true) {
    val id = requestId.take(128).replace('\n', ' ').replace('\r', ' ')
    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    timingLogger.info("Reload timing requestId=$id stage=$stage elapsedMs=$elapsed" +
        (bytes?.let { " bytes=$it" } ?: "") + " outcome=${if (success) "complete" else "failed"}")
}

internal fun <T> timedReload(requestId: String, stage: String, block: () -> T): T {
    val started = System.nanoTime()
    var success = false
    try { return block().also { success = true } }
    finally { recordReloadTiming(requestId, stage, started, success = success) }
}
