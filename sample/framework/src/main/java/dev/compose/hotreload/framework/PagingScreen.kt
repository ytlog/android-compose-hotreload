package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class PagingAudit {
    val identity = token()
    var sources by mutableIntStateOf(0)
    var loads by mutableIntStateOf(0)
    var pending by mutableIntStateOf(-1)
    var failNext by mutableStateOf(false)
    var gate: CompletableDeferred<Unit>? = null
}

data class FeatureRow(val id: Int, val label: String)

class FeaturePagingSource(private val audit: PagingAudit) : PagingSource<Int, FeatureRow>() {
    init { audit.sources++ }
    override fun getRefreshKey(state: PagingState<Int, FeatureRow>): Int? =
        state.anchorPosition?.let { (it / 10) * 10 }
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FeatureRow> {
        val start = params.key ?: 0
        audit.loads++
        if (start >= 20) {
            val gate = CompletableDeferred<Unit>()
            audit.gate = gate; audit.pending = start
            try { gate.await() } finally { audit.gate = null; audit.pending = -1 }
        }
        if (audit.failNext) {
            audit.failNext = false
            return LoadResult.Error(IllegalStateException("fixture retry"))
        }
        return LoadResult.Page((start until start + params.loadSize).map { FeatureRow(it, pagingLabel(it)) },
            prevKey = if (start == 0) null else (start - 10).coerceAtLeast(0), nextKey = if (start >= 40) null else start + params.loadSize)
    }
}

@Composable
fun PagingScreen() {
    val audit = remember { PagingAudit() }
    val scope = rememberCoroutineScope()
    val flow = remember { Pager(PagingConfig(pageSize = 10, initialLoadSize = 20, prefetchDistance = 1)) {
        FeaturePagingSource(audit)
    }.flow.cachedIn(scope) }
    val items = flow.collectAsLazyPagingItems()
    val list = rememberLazyListState()
    val pager = rememberPagerState(initialPage = 2) { 6 }
    var draft by remember { mutableStateOf("paging-draft") }
    Column {
        Text("Paging anchor: ${audit.identity}|$draft|${System.identityHashCode(list)}:${System.identityHashCode(pager)}", fontSize = 10.sp)
        Text("Paging audit: ${audit.sources}:${audit.loads}:${audit.pending}:${items.itemCount}", fontSize = 10.sp)
        Text("Paging tail: ${items.itemSnapshotList.items.lastOrNull()?.label}", fontSize = 10.sp)
        Text("Paging position: ${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}", fontSize = 10.sp)
        Text("Paging status: ${items.loadState.append::class.simpleName}:${items.loadState.refresh::class.simpleName}", fontSize = 10.sp)
        Row {
            Button(onClick = { draft = "edited-paging"; scope.launch { list.scrollToItem(18, 7) } }) { Text("Load more") }
            Button(onClick = { audit.gate?.complete(Unit) }) { Text("Release page") }
        }
        Row {
            Button(onClick = { audit.failNext = true; scope.launch { list.scrollToItem(28, 7) } }) { Text("Fail append") }
            Button(onClick = { items.retry() }) { Text("Retry page") }
            Button(onClick = { items.refresh() }) { Text("Refresh page") }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(100.dp)) {
            items(items.itemCount, key = items.itemKey { it.id }) { index ->
                val row = items[index]
                val identity = remember { token() }
                Text("Item: ${row?.label}:$identity", modifier = Modifier.height(32.dp), fontSize = 10.sp)
            }
        }
        Text("Pager position: ${pager.currentPage}:${pager.currentPageOffsetFraction}", fontSize = 10.sp)
        Button(onClick = { scope.launch { pager.scrollToPage(3) } }) { Text("Pager next") }
        HorizontalPager(state = pager, modifier = Modifier.height(130.dp).fillMaxWidth()) { page ->
            val identity = remember { token() }
            var count by remember { mutableIntStateOf(0) }
            val click = remember { { count += 1 } }
            Column {
                Text("Pager state: $page:$identity:$count", fontSize = 10.sp)
                Text(pagerLabel(page))
                Button(onClick = click) { Text("Pager count $page") }
            }
        }
    }
}
