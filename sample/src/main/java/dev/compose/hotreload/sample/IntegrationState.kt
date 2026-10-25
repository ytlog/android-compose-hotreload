package dev.compose.hotreload.sample

import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.*
import retrofit2.converter.scalars.ScalarsConverterFactory

/** Stable owner. All UI/reload side effects are recorded independently of edited functions. */
@Stable
class IntegrationState(private val saved: SavedStateHandle) : ViewModel() {
    val token = UUID.randomUUID().toString().take(8)
    val events = mutableStateListOf<String>()
    var route by mutableStateOf(saved.get<String>("route") ?: "list")
    var draft by mutableStateOf(saved.get<String>("draft") ?: "")
    var callbackValue by mutableStateOf("first")
    var effectKey by mutableIntStateOf(1)
    var enabled by mutableStateOf(true)
    var collections by mutableIntStateOf(0)
    var probe by mutableStateOf("none")
    private val guard: Any = Class.forName("dev.compose.hotreload.sample.StateAdmissionGuard").getDeclaredConstructor().newInstance()
    private var sequence = 0
    private val gates = linkedMapOf<String, CompletableDeferred<String>>()
    private val listeners = linkedMapOf<String, () -> String>()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()
    private fun retrofit() = Retrofit.Builder().baseUrl("http://127.0.0.1:18765/")
        .client(client).addConverterFactory(ScalarsConverterFactory.create()).build()
    private val api = retrofit().create(IntegrationApi::class.java)
    private val gson = Gson()
    private val adapter = gson.getAdapter(IntegrationDto::class.java)
    private val generated: KSerializer<IntegrationGenerated> = IntegrationGenerated.serializer()
    private var call: Job? = null
    private val running = AtomicBoolean(false)
    private val workerValues = ConcurrentLinkedQueue<String>()
    private var worker: Thread? = null
    fun event(value: String) { events.add(value) }
    fun enter() { route = "detail:42"; saved["route"] = route }
    fun back() { route = "list"; saved["route"] = route }
    fun editDraft(value: String) { draft = value; saved["draft"] = value }
    fun register(callback: () -> String): String {
        val id = "l${++sequence}"; listeners[id] = callback; event("register:$id"); return id
    }
    fun unregister(id: String) { check(listeners.remove(id) != null); event("dispose:$id") }
    fun fire() { listeners.forEach { (id, callback) -> event("callback:$id:${callback()}") } }
    fun begin(kind: String): String {
        val id = "$kind${++sequence}"; gates[id] = CompletableDeferred(); event("start:$id"); return id
    }
    suspend fun await(id: String): String = gates.getValue(id).await()
    fun output(id: String, value: String) { event("output:$id:$value") }
    fun end(id: String) { check(gates.remove(id) != null); event("end:$id") }
    fun reply() { gates.entries.first { it.key.startsWith("effect") && !it.value.isCompleted }.value.complete("reply") }
    fun network(fresh: Boolean) {
        check(call?.isActive != true)
        val service = if (fresh) retrofit().create(IntegrationApi::class.java) else api
        call = owner.launch {
            val id = "http${++sequence}"; event("request:$id")
            try {
                val body = suspendCancellableCoroutine<String> { continuation ->
                    val request = service.read()
                    continuation.invokeOnCancellation { request.cancel() }
                    request.enqueue(object : Callback<String> {
                        override fun onResponse(call: Call<String>, response: Response<String>) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(requireNotNull(response.body())))
                        }
                        override fun onFailure(call: Call<String>, failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                        }
                    })
                }
                event("response:$id:${networkResult(body)}")
            } catch (cancelled: CancellationException) { event("cancel:$id"); throw cancelled }
            catch (failure: Exception) { android.util.Log.w("IntegrationNetwork", "Request failed", failure); event("error:$id:${failure.javaClass.simpleName}") }
            finally { event("finish:$id") }
        }
    }
    fun cancelNetwork() { call?.cancel() }
    fun json(fresh: Boolean) {
        val a = if (fresh) Gson().getAdapter(IntegrationDto::class.java) else adapter
        try { probe = "json:" + a.fromJson("{\"value\":\"42\",\"extra\":7}").display() }
        catch (failure: Exception) { probe = "json-error:${failure.javaClass.simpleName}" }
        catch (failure: LinkageError) { probe = "json-error:${failure.javaClass.simpleName}" }
    }
    fun generated(fresh: Boolean) {
        val s = if (fresh) IntegrationGenerated.serializer() else generated
        try { probe = "generated:" + Json { encodeDefaults = true }.encodeToString(s, Json.decodeFromString(s, "{\"value\":\"wire\"}")) }
        catch (failure: Exception) { probe = "generated-error:${failure.javaClass.simpleName}" }
    }
    fun build() { probe = integrationBuildResult() }
    fun batch() { probe = integrationBatchResult() }
    fun session() { probe = sessionResult(); event(probe) }
    fun startWorker() {
        check(running.compareAndSet(false, true)); workerValues.clear()
        worker = Thread {
            while (running.get()) { workerValues.add(sessionResult()); Thread.sleep(10) }
        }.apply { name = "integration-worker"; start() }
    }
    fun stopWorker() {
        running.set(false); worker?.join(3000); check(worker?.isAlive == false)
        probe = "worker:" + workerValues.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
    }
    fun collectGarbage() { Runtime.getRuntime().gc(); collections++ }
    fun counts() = "${listeners.size}/${gates.size}"
    override fun onCleared() { running.set(false); owner.cancel(); client.dispatcher.executorService.shutdown() }
}
