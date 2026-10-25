package dev.compose.hotreload.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Stable owner and ledger. Reload never launches, cancels, or reconstructs a task here. */
class CoroutineState : ViewModel() {
    val token = UUID.randomUUID().toString().take(8)
    val ledger = CoroutineLedger()
    val work = CoroutineWork()
    val flows = CoroutineFlows()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure -> ledger.event("uncaught:${failure.javaClass.simpleName}") })
    private val anchors = listOf(work, flows, ledger)
    private val admissionGuard: Any = Class.forName("dev.compose.hotreload.sample.StateAdmissionGuard").getDeclaredConstructor().newInstance()
    private val jobs = linkedMapOf<String, Job>()
    private val cold = flows.cold(ledger, "cold-old")
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val state = MutableStateFlow("s0")
    var draft by mutableStateOf("")
    var collections by mutableIntStateOf(0)
    var taskNumber = 0; private set
    var collectorNumber = 0; private set
    var emission = 0; private set
    private var lastTask = ""
    private val outputs = linkedMapOf<String, MutableList<String>>()
    val retained get() = work === anchors[0] && flows === anchors[1] && ledger === anchors[2]
    fun start(kind: String) {
        val id = if (kind == "cold-old") kind else "t${++taskNumber}"
        check(!jobs.containsKey(id))
        outputs[id] = mutableListOf()
        lastTask = id
        ledger.event("start:$id")
        val job = if (kind == "lambda") work.lambda(scope, ledger, id) { record(id, it) }
        else scope.launch {
            when (kind) {
                "named" -> record(id, work.named(ledger, id))
                "nested" -> record(id, work.nested(ledger, id))
                "guarded" -> record(id, work.guarded(ledger, id))
                "cold-old" -> cold.collect { record(id, it) }
                "cold-new" -> flows.cold(ledger, id).collect { record(id, it) }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { cause ->
            ledger.event("end:$id:" + when (cause) { null -> "ok"; is CancellationException -> "cancel"; else -> cause.javaClass.simpleName })
        }
    }
    fun collect(kind: String) {
        val id = "c${++collectorNumber}"
        outputs[id] = mutableListOf()
        val job = scope.launch {
            if (kind == "gated") flows.gated(events, ledger, id) { record(id, it) }
            else flows.collect(if (kind == "state") state else events, ledger, id) { record(id, it) }
        }
        jobs[id] = job
        job.invokeOnCompletion { cause -> ledger.event("end:$id:" + if (cause is CancellationException) "cancel" else "unexpected") }
    }
    private fun record(id: String, value: String) { outputs.getValue(id).add(value); ledger.event("output:$id:$value") }
    fun emitEvent() { val value = "e${++emission}"; check(events.tryEmit(value)); ledger.event("emit:$value") }
    fun emitState() { state.value = "s1"; ledger.event("state:s1") }
    fun cancelTask() { jobs.getValue(lastTask).cancel() }
    fun cancelCollector() { jobs.filter { it.key.startsWith("c") }.values.forEach { it.cancel() } }
    fun report(): List<String> {
        ledger.revision
        return listOf("Counts: ${ledger.counts()}", "Pending: ${ledger.pendingIds()}",
            "Starts: $taskNumber/$collectorNumber/$emission", "Trace: ${ledger.events.joinToString(";")}") +
            outputs.entries.toList().takeLast(2).map { "${it.key}: ${it.value.joinToString(",").ifEmpty { "pending" }}" }
    }
    fun collectGarbage() { Runtime.getRuntime().gc(); collections++ }
    override fun onCleared() { scope.cancel() }
}
