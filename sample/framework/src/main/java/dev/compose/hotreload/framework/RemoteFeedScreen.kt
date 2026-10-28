package dev.compose.hotreload.framework

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object RemoteFeedAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
}

class RemoteFeedState(context: Context) : AutoCloseable {
    val identity = token()
    val database = Room.inMemoryDatabaseBuilder(context, RemoteFeedDatabase::class.java).build()
    val server = RemoteFeedServer()
    val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.MINUTES).retryOnConnectionFailure(false).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var commits by mutableIntStateOf(0)
    var errors by mutableIntStateOf(0)
    var sources by mutableIntStateOf(0)
    var draft by mutableStateOf("feed-draft")
    override fun close() {
        scope.cancel(); server.close(); database.close()
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
}

@OptIn(ExperimentalPagingApi::class)
class FixtureRemoteMediator(private val page: RemoteFeedState) : RemoteMediator<Int, RemoteFeedRow>() {
    override suspend fun load(loadType: LoadType, state: PagingState<Int, RemoteFeedRow>): MediatorResult {
        if (loadType == LoadType.PREPEND) return MediatorResult.Success(true)
        val dao = page.database.feed()
        val offset = if (loadType == LoadType.REFRESH) 0 else (dao.lastId() ?: -1) + 1
        if (offset >= 40) return MediatorResult.Success(true)
        return try {
            val ids = withContext(Dispatchers.IO) {
                val request = Request.Builder().url("http://127.0.0.1:${page.server.port}/feed?offset=$offset").build()
                page.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body!!.string().split(',').map(String::toInt)
                }
            }
            // Mapping occurs after the gated HTTP response, not when the request starts.
            val rows = ids.map { RemoteFeedRow(it, remoteFeedLabel(it)) }
            page.database.withTransaction {
                if (loadType == LoadType.REFRESH) dao.clear()
                dao.store(rows)
            }
            page.commits++
            MediatorResult.Success(ids.last() >= 39)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            android.util.Log.w("RemoteFeedFixture", "HTTP load failed", error)
            page.errors++
            MediatorResult.Error(error)
        }
    }
}

@OptIn(ExperimentalPagingApi::class)
@Composable
fun RemoteFeedScreen() {
    val context = LocalContext.current
    val page = remember { RemoteFeedState(context) }
    val mediator = remember { FixtureRemoteMediator(page) }
    val flow = remember {
        Pager(PagingConfig(pageSize = 10, initialLoadSize = 20, prefetchDistance = 1, enablePlaceholders = false),
            remoteMediator = mediator) {
            page.sources++
            page.database.feed().pages()
        }.flow.cachedIn(page.scope)
    }
    DisposableEffect(page) {
        page.server.start(); RemoteFeedAudit.created++
        onDispose { page.close(); RemoteFeedAudit.disposed++ }
    }
    val items = flow.collectAsLazyPagingItems()
    val count by remember { page.database.feed().count() }.collectAsState(-1)
    val tail by remember { page.database.feed().tail() }.collectAsState(null)
    val list = rememberLazyListState()
    Column {
        Text("Feed anchor: ${page.identity}:${System.identityHashCode(page.database)}:${System.identityHashCode(mediator)}:${System.identityHashCode(flow)}:${System.identityHashCode(list)}", fontSize = 10.sp)
        Text("Feed draft: ${page.draft}", fontSize = 10.sp)
        Text("Feed network: ${page.server.requests}:${page.server.replies}:${page.server.pending}", fontSize = 10.sp)
        Text("Feed offsets: ${page.server.offsets}", fontSize = 10.sp)
        Text("Feed storage: $count:${page.commits}:${page.errors}:$tail", fontSize = 10.sp)
        Text("Feed sources: ${page.sources}", fontSize = 10.sp)
        Text("Feed position: ${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}", fontSize = 10.sp)
        Text("Feed visible: ${list.layoutInfo.visibleItemsInfo.firstOrNull()?.key}:${list.firstVisibleItemScrollOffset}", fontSize = 10.sp)
        Text("Feed status: ${items.loadState.mediator?.refresh?.javaClass?.simpleName}:${items.loadState.mediator?.append?.javaClass?.simpleName}", fontSize = 10.sp)
        Row {
            Button(onClick = { page.draft = "edited-feed"; page.scope.launch { list.scrollToItem(18, 7) } }) { Text("Feed append") }
            Button(onClick = { page.server.release() }) { Text("Feed release") }
        }
        Row {
            Button(onClick = { page.server.failNext = true; page.scope.launch { list.scrollToItem(28, 7) } }) { Text("Feed fail append") }
            Button(onClick = { items.retry() }) { Text("Feed retry") }
        }
        Button(onClick = { page.server.failNext = true; page.scope.launch { list.scrollToItem(0); items.refresh() } }) { Text("Feed fail refresh") }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(150.dp)) {
            items(items.itemCount, key = items.itemKey { it.id }) { index ->
                val row = items[index]
                val token = remember { token() }
                Text("Feed row: ${row?.id}:${row?.label}:$token", fontSize = 10.sp, modifier = Modifier.height(40.dp))
            }
        }
    }
}
