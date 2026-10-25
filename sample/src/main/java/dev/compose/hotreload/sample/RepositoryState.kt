package dev.compose.hotreload.sample

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

/** Unedited owner: actual Koin container, injected consumer, proxy and Method anchors survive updates. */
class RepositoryState : ViewModel() {
    val token = UUID.randomUUID().toString().take(8)
    private val direct = EditRepositoryImpl()
    private val graph = repositoryContainer()
    private val injected = graph.koin.get<EditRepository>()
    private val client = graph.koin.get<RepositoryClient>()
    private val handler = RepositoryHandler(direct)
    private val proxy = Proxy.newProxyInstance(EditRepository::class.java.classLoader, arrayOf(EditRepository::class.java), handler) as EditRepository
    private val readMethod = EditRepository::class.java.getMethod("read", String::class.java)
    private var addedMethod: Method? = null
    private val anchors: List<Any> = listOf(direct, graph, injected, client, handler, proxy, readMethod)
    private val admissionGuard: Any = Class.forName("dev.compose.hotreload.sample.StateAdmissionGuard").getDeclaredConstructor().newInstance()
    var draft by mutableStateOf("")
    var seeded by mutableStateOf(false)
    var calls by mutableIntStateOf(0)
    var collections by mutableIntStateOf(0)
    var report by mutableStateOf(emptyList<String>())
    private var lastCounts = RepositoryLedger.counts()
    val retained get() = listOf(direct, graph, injected, client, handler, proxy, readMethod).zip(anchors).all { (a,b) -> a === b }
    fun seed() {
        check(!seeded); direct.value = draft; (injected as EditRepositoryImpl).value = draft; seeded = true
    }
    private fun cached(method: Method, target: Any, argument: Any): String = try {
        method.invoke(target, argument).toString()
    } catch (failure: InvocationTargetException) { failure.targetException.javaClass.simpleName }
      catch (failure: Throwable) { failure.javaClass.simpleName }
    fun inspect() {
        calls++
        try {
            check(retained && client.repository === injected && handler.target === direct)
            check(RepositoryLedger.counts() == lastCounts) { "Work replayed between calls" }
            val fresh = EditRepositoryImpl()
            val oldFactoryClient = graph.koin.get<RepositoryClient>()
            check(oldFactoryClient !== client && oldFactoryClient.repository === injected)
            check(graph.koin.get<EditRepository>() === injected)
            val freshGraph = repositoryContainer()
            try {
                val freshClient = freshGraph.koin.get<RepositoryClient>()
                check(freshClient.repository !== injected)
                val freshHandler = RepositoryHandler(fresh)
                val freshProxy = Proxy.newProxyInstance(EditRepository::class.java.classLoader, arrayOf(EditRepository::class.java), freshHandler) as EditRepository
                check(freshProxy !== proxy && freshProxy.javaClass === proxy.javaClass)
                check(Proxy.getInvocationHandler(proxy) === handler)
                check(proxy == proxy && proxy != freshProxy && proxy.hashCode() == System.identityHashCode(proxy) && proxy.toString() == "RepositoryProxy")
                val lanes = listOf(direct, fresh, injected, oldFactoryClient.repository, freshClient.repository, proxy, freshProxy)
                val failures = lanes.map { repository ->
                    try { repository.read("fail"); "missing-error" } catch (failure: Throwable) { failure.javaClass.simpleName }
                }
                val values = listOf(
                    "Direct old: " + repositoryValues(direct, draft), "Direct new: " + repositoryValues(fresh, draft),
                    "DI old: " + client.inspect(draft), "DI factory: " + oldFactoryClient.inspect(draft),
                    "DI new graph: " + freshClient.inspect(draft), "Proxy old: " + repositoryValues(proxy, draft),
                    "Proxy new: " + repositoryValues(freshProxy, draft),
                    "Cached read: " + cached(readMethod, direct, draft) + "|" + cached(readMethod, proxy, draft),
                    "Errors: " + failures.joinToString(","))
                if (addedMethod == null) addedMethod = try { EditRepository::class.java.getMethod("version", String::class.java) } catch (_: NoSuchMethodException) { null }
                val added = addedMethod?.let { cached(it, proxy, draft) } ?: "absent"
                lastCounts = RepositoryLedger.counts()
                report = values + listOf("Cached version: $added", "Ledger: " + lastCounts.joinToString("/"),
                    "DI retained: true", "Proxy class reused: true")
            } finally { freshGraph.close() }
        } catch (failure: Throwable) {
            android.util.Log.e("RepositoryProbe", "Inspection failed", failure)
            report = listOf("Probe failure: ${failure.javaClass.simpleName}")
        }
        android.util.Log.i("RepositoryProbe", "call=$calls ledger=${RepositoryLedger.counts()} report=$report")
    }
    fun collectGarbage() { Runtime.getRuntime().gc(); collections++ }
    override fun onCleared() { graph.close() }
}
