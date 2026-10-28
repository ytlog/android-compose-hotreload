package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object SearchAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
    var active by mutableIntStateOf(0)
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchState {
    val identity = token()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val query = MutableStateFlow("")
    val pending = mutableMapOf<String, CompletableDeferred<String>>()
    var requests by mutableStateOf("")
    var cancelled by mutableStateOf("")
    var delivered by mutableStateOf("")
    var result by mutableStateOf("none")
    var responses by mutableIntStateOf(0)
    var job: Job? = null
    val mapper: (String) -> String = { searchResult(it) }
    fun start() {
        check(job == null)
        job = scope.launch {
            SearchAudit.active++
            try {
                query.debounce(250).filter { it.isNotEmpty() }.flatMapLatest { q ->
                    flow {
                        requests += "$q;"
                        val response = CompletableDeferred<String>()
                        pending[q] = response
                        try { emit(response.await()) }
                        catch (error: CancellationException) { cancelled += "$q;"; throw error }
                    }
                }.collect { value ->
                    result = mapper(value)
                    delivered += "$result;"
                }
            } finally { SearchAudit.active-- }
        }
    }
    fun burst() { scope.launch { query.value = "a"; delay(40); query.value = "ab"; delay(40); query.value = "abc" } }
    fun respond(q: String) { check(pending.getValue(q).complete(q)); responses++ }
    fun close() { scope.cancel(); pending.values.forEach { it.cancel() } }
}

@Composable
fun SearchScreen() {
    val page = remember { SearchState() }
    DisposableEffect(page) { page.start(); SearchAudit.created++; onDispose { page.close(); SearchAudit.disposed++ } }
    val query by page.query.collectAsState()
    Column {
        Text("Search anchor: ${page.identity}:${System.identityHashCode(page.job)}:${System.identityHashCode(page.mapper)}", fontSize = 10.sp)
        Text("Search query: $query", fontSize = 10.sp)
        Text("Search requests: ${page.requests}", fontSize = 10.sp)
        Text("Search cancelled: ${page.cancelled}", fontSize = 10.sp)
        Text("Search deliveries: ${page.delivered}", fontSize = 10.sp)
        Text("Search result: ${page.result}", fontSize = 10.sp)
        Text("Search responses: ${page.responses}", fontSize = 10.sp)
        Button(onClick = { page.burst() }) { Text("Search burst") }
        Button(onClick = { page.query.value = "latest" }) { Text("Search latest") }
        Button(onClick = { page.respond("abc") }) { Text("Release stale") }
        Button(onClick = { page.respond("latest") }) { Text("Release latest") }
        Button(onClick = { page.query.value = "final" }) { Text("Search final") }
        Button(onClick = { page.respond("final") }) { Text("Release final") }
    }
}
