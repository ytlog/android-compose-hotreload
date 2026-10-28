package dev.compose.hotreload.framework

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CancellationException

object NavExtensionAudit { var created by mutableIntStateOf(0); var disposed by mutableIntStateOf(0) }
class NavExtensionState {
    val identity = token()
    val stack = mutableStateListOf("home")
    var links by mutableIntStateOf(0)
    var starts by mutableIntStateOf(0)
    var cancels by mutableIntStateOf(0)
    var commits by mutableIntStateOf(0)
    var progress by mutableFloatStateOf(0f)
    var event by mutableStateOf("none")
    fun accept(uri: Uri?) {
        if (uri == null) return
        require(uri.scheme == "hotreload-fixture" && uri.host == "nav" && uri.pathSegments.size == 2 && uri.pathSegments[0] == "item")
        val id = uri.pathSegments[1]; require(id.toIntOrNull() != null)
        stack.add(id); links++
    }
}
class NavExtensionActivity : ComponentActivity() {
    private val page = NavExtensionState()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        page.accept(intent.data)
        setContent { MaterialTheme { NavExtensionScreen(page) { finish() } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); page.accept(intent.data) }
}
@Composable
fun NavExtensionScreen(page: NavExtensionState, finish: () -> Unit) {
    val root = remember { token() }
    DisposableEffect(page) { NavExtensionAudit.created++; onDispose { NavExtensionAudit.disposed++ } }
    Column {
        Text("Nav extension: $root:${page.identity}:${System.identityHashCode(page.stack)}", fontSize = 10.sp)
        Text("Nav stack: ${page.stack.joinToString(",")}", fontSize = 10.sp)
        Text("Nav links: ${page.links}", fontSize = 10.sp)
        Text("Nav back: ${page.starts}:${page.cancels}:${page.commits}:${page.event}", fontSize = 10.sp)
        Text("Nav progress: ${page.progress}", fontSize = 10.sp)
        Button(onClick = finish) { Text("Close nav fixture") }
        NavDisplay(backStack = page.stack, onBack = { page.stack.removeAt(page.stack.lastIndex) },
            modifier = Modifier.weight(1f), entryProvider = { id ->
                NavEntry(id) {
                    val live = remember { token() }
                    val saved = rememberSaveable { token() }
                    var draft by rememberSaveable { mutableStateOf("draft-$id") }
                    var clicks by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("Nav page $id: $saved:$draft:$clicks", fontSize = 10.sp)
                        Text("Nav live $id: $live", fontSize = 10.sp)
                        Text(navExtensionLabel(id), fontSize = 10.sp)
                        Button(onClick = { draft = "edited-$id"; clicks++ }) { Text("Seed nav $id") }
                        if (id == "home") Button(onClick = { page.stack.add("42") }) { Text("Open nav 42") }
                    }
                }
            })
    }
    // Register after NavDisplay so this screen's real system gesture handler owns back.
    PredictiveBackHandler(enabled = page.stack.size > 1) { events ->
        page.starts++
        try {
            events.collect { event -> page.progress = event.progress; page.event = predictiveLabel() }
            page.stack.removeAt(page.stack.lastIndex); page.commits++; page.progress = 0f
        } catch (cancelled: CancellationException) {
            page.cancels++; page.progress = 0f; throw cancelled
        }
    }
}
