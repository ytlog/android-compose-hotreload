package dev.compose.hotreload.framework

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object SessionSignals {
    private val callbacks = mutableListOf<() -> Unit>()
    var registrations = 0
    var removals = 0
    val active get() = callbacks.size
    fun register(callback: () -> Unit): AutoCloseable {
        check(callback !in callbacks); callbacks += callback; registrations++
        return AutoCloseable { check(callbacks.remove(callback)); removals++ }
    }
    fun send() { callbacks.toList().forEach { it() } }
}

object LongSessionAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
    var workers by mutableIntStateOf(0)
}

class LongSessionState {
    val identity = token()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val versions = ConcurrentHashMap.newKeySet<String>()
    val ticks = AtomicLong()
    @Volatile var lastVersion = "none"
    var draft by mutableStateOf("session-draft")
    var calls by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var result by mutableStateOf("none")
    var report by mutableStateOf("{}")
    val callback: () -> Unit = { calls++; total += longSessionStep(); result = "${longSessionVersion()}:$draft" }
    private val handler = Handler(Looper.getMainLooper())
    var foreground = true
    var lastBeat = 0L
    var maxGap = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (foreground && lastBeat != 0L) maxGap = maxOf(maxGap, now - lastBeat)
            lastBeat = if (foreground) now else 0L
            handler.postDelayed(this, 100)
        }
    }
    var job: Job? = null
    fun start() {
        check(job == null)
        handler.post(heartbeat)
        LongSessionAudit.workers++
        job = scope.launch {
            try {
                while (isActive) {
                    val version = longSessionVersion()
                    versions += version; lastVersion = version; ticks.incrementAndGet()
                    delay(100)
                }
            } finally { LongSessionAudit.workers-- }
        }
    }
    fun metrics() {
        val runtime = Runtime.getRuntime()
        report = JSONObject().apply {
            put("heap", runtime.totalMemory() - runtime.freeMemory())
            put("native", Debug.getNativeHeapAllocatedSize())
            put("threads", File("/proc/self/task").list()!!.size)
            put("fds", File("/proc/self/fd").list()!!.size)
            put("gapMs", maxGap)
            put("ticks", ticks.get())
            put("lastVersion", lastVersion)
            put("versions", versions.sortedBy { it.substringAfter('v').toInt() }.joinToString(","))
            put("workers", LongSessionAudit.workers)
            put("listeners", SessionSignals.active)
            put("registrations", SessionSignals.registrations)
            put("removals", SessionSignals.removals)
        }.toString()
    }
    fun close() { handler.removeCallbacks(heartbeat); scope.cancel() }
}

@Composable
fun LongSessionScreen() {
    val state = remember { LongSessionState() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(state, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { state.foreground = false; state.lastBeat = 0 }
            if (event == Lifecycle.Event.ON_START) { state.foreground = true; state.lastBeat = 0 }
        }
        owner.lifecycle.addObserver(observer)
        state.start(); LongSessionAudit.created++
        val registration = SessionSignals.register(state.callback)
        onDispose {
            owner.lifecycle.removeObserver(observer); registration.close(); state.close(); LongSessionAudit.disposed++
        }
    }
    Column {
        Text("Session anchor: ${state.identity}:${System.identityHashCode(state.callback)}:${System.identityHashCode(state.scope)}:${System.identityHashCode(state.job)}", fontSize = 10.sp)
        Text("Session draft: ${state.draft}", fontSize = 10.sp)
        Text("Session counts: ${state.calls}:${state.total}", fontSize = 10.sp)
        Text("Session result: ${state.result}", fontSize = 10.sp)
        Button(onClick = { state.draft = "edited-session" }) { Text("Seed session") }
        Button(onClick = { SessionSignals.send() }) { Text("Session event") }
        Button(onClick = { state.metrics() }) { Text("Session metrics") }
        Text("Session metrics: ${state.report}", fontSize = 8.sp)
    }
}
