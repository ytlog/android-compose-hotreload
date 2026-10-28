package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

object ScrollAudit { var created by mutableIntStateOf(0); var disposed by mutableIntStateOf(0) }

class ScrollState {
    val identity = token()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var collapsed by mutableFloatStateOf(0f)
    var event by mutableStateOf("none")
    var refreshing by mutableStateOf(false)
    var requests by mutableIntStateOf(0)
    var completed by mutableIntStateOf(0)
    var result by mutableStateOf("none")
    var gate: CompletableDeferred<Unit>? = null
    var job: Job? = null
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            event = scrollLabel()
            val before = collapsed
            collapsed = (before - available.y).coerceIn(0f, 100f)
            return Offset(0f, before - collapsed)
        }
    }
    val refresh: () -> Unit = {
        check(!refreshing)
        refreshing = true; requests++
        val pending = CompletableDeferred<Unit>(); gate = pending
        job = scope.launch {
            try { pending.await(); result = refreshLabel(); completed++ }
            finally { refreshing = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollScreen() {
    val page = remember { ScrollState() }
    val list = rememberLazyListState()
    val pull = rememberPullToRefreshState()
    var draft by remember { mutableStateOf("scroll-draft") }
    DisposableEffect(page) { ScrollAudit.created++; onDispose { page.scope.cancel(); ScrollAudit.disposed++ } }
    Column {
        Text("Scroll anchor: ${page.identity}:${System.identityHashCode(page.connection)}:${System.identityHashCode(list)}:${System.identityHashCode(pull)}:${System.identityHashCode(page.refresh)}", fontSize = 9.sp)
        Text("Scroll draft: $draft", fontSize = 10.sp)
        Text("Scroll position: ${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}:${page.collapsed.toInt()}", fontSize = 10.sp)
        Text("Scroll event: ${page.event}", fontSize = 10.sp)
        Text("Scroll refresh: ${page.refreshing}:${page.requests}:${page.completed}:${page.result}", fontSize = 10.sp)
        Text("Scroll job: ${System.identityHashCode(page.job)}", fontSize = 10.sp)
        Row {
            Button(onClick = { draft = "edited-scroll" }) { Text("Seed scroll") }
            Button(onClick = { page.scope.launch { list.scrollToItem(0) }; page.collapsed = 0f }) { Text("Scroll top") }
            Button(onClick = { check(page.gate!!.complete(Unit)) }) { Text("Finish refresh") }
        }
        Box(Modifier.fillMaxWidth().height((60f - page.collapsed * .4f).dp)) { Text("Collapsing header") }
        PullToRefreshBox(isRefreshing = page.refreshing, onRefresh = page.refresh,
            state = pull, modifier = Modifier.fillMaxWidth().height(300.dp).nestedScroll(page.connection)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(80, key = { it }) { row ->
                    val identity = remember { token() }
                    Text("Scroll row $row:$identity", modifier = Modifier.fillMaxWidth().height(40.dp))
                }
            }
        }
    }
}
