package dev.compose.hotreload.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Never redefined: counts actual requests and external completions, including late callbacks. */
class CoroutineLedger {
    var revision by mutableIntStateOf(0)
        private set
    val events = mutableListOf<String>()
    private val pending = linkedMapOf<String, CancellableContinuation<String>>()
    private val seen = mutableSetOf<String>()
    var requests = 0; private set
    var deliveries = 0; private set
    var cancellations = 0; private set
    var ignored = 0; private set
    fun event(value: String) { events += value; revision++ }
    suspend fun await(id: String): String = suspendCancellableCoroutine { continuation ->
        check(seen.add(id)) { "Duplicate request: $id" }
        requests++
        pending[id] = continuation
        event("request:$id")
        continuation.invokeOnCancellation { cancellations++; event("cancel-request:$id") }
    }
    fun release(fail: Boolean = false) {
        val entry = pending.entries.firstOrNull() ?: error("No pending request")
        pending.remove(entry.key)
        if (!entry.value.isActive) { ignored++; event("late:${entry.key}"); return }
        deliveries++
        event("deliver:${entry.key}")
        if (fail) entry.value.resumeWithException(IllegalStateException("remote-error"))
        else entry.value.resume("reply")
    }
    fun pendingIds(): String = pending.keys.joinToString(",").ifEmpty { "none" }
    fun counts() = "$requests/$deliveries/$cancellations/$ignored"
}
