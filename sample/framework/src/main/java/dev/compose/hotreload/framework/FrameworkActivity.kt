package dev.compose.hotreload.framework
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FrameworkActivity : ComponentActivity() {
    fun activityLabel(): String = "Activity v1"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { FrameworkScreen() } }
    }
}

@Composable
fun FrameworkScreen() {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val root = remember { token() }
    var gc by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Framework edits")
        Text((LocalContext.current as FrameworkActivity).activityLabel(), fontSize = 10.sp)
        Text("Root: $root", fontSize = 10.sp)
        Text("Route: ${current?.destination?.route}", fontSize = 10.sp)
        Row {
            Button(onClick = { System.gc(); gc++ }) { Text("GC: $gc") }
            Button(onClick = { nav.popBackStack() }) { Text("Back") }
        }
        NavHost(nav, startDestination = "list", modifier = Modifier.weight(1f),
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
            composable("list") {
                Column {
                    Text("Draft list")
                    Button(onClick = { nav.navigate("detail/42") }) { Text("Open 42") }
                    Button(onClick = { nav.navigate("detail/43") }) { Text("Open 43") }
                    Button(onClick = { nav.navigate("interop") }) { Text("Open interop") }
                    Text("Interop audit: ${interopAuditSummary()}", fontSize = 10.sp)
                }
            }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
                val model: FrameworkModel = hiltViewModel(entry)
                FrameworkForm(model, entry.id) { nav.navigate("review") }
            }
            composable("review") { Text("Review page") }
            composable("interop") { InteropScreen() }
        }
        Text("Ledger: ${FrameworkLedger.events.joinToString(";")}", fontSize = 8.sp, maxLines = 2)
    }
}

@Composable
fun FrameworkForm(model: FrameworkModel, entry: String, review: () -> Unit) {
    val focus = LocalFocusManager.current
    val local = rememberSaveable { token() }
    val row by model.observed.collectAsStateWithLifecycle(initialValue = "loading")
    DisposableEffect(model) {
        FrameworkLedger.record("enter:${model.identity}")
        onDispose { FrameworkLedger.record("leave:${model.identity}") }
    }
    Column {
        Text("Entry: $entry", fontSize = 10.sp)
        Text("Model: ${model.identity}", fontSize = 10.sp)
        Text("Local: $local", fontSize = 10.sp)
        Text("Id: ${model.id}", fontSize = 10.sp)
        Text("Anchors: ${model.anchors()}", fontSize = 10.sp)
        TextField(value = model.field, onValueChange = { model.edit(it) }, label = { Text("Draft") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { model.focused = it.isFocused })
        Row {
            Button(onClick = { focus.clearFocus() }) { Text("Ready") }
            Button(onClick = { model.business() }) { Text("Business") }
            Button(onClick = { model.call() }) { Text("Callback") }
        }
        Row {
            Button(onClick = { model.save() }) { Text("Save") }
            Button(onClick = { model.query() }) { Text("Query") }
            Button(onClick = { model.binding() }) { Text("Binding") }
        }
        Button(onClick = review) { Text("Review") }
        Text("Text: ${model.field.text}", fontSize = 10.sp)
        Text("Selection: ${model.field.selection.start}:${model.field.selection.end}", fontSize = 10.sp)
        Text("Composition: ${model.field.composition?.let { "${it.start}:${it.end}" } ?: "none"}", fontSize = 10.sp)
        Text("Focused: ${model.focused}", fontSize = 10.sp)
        Text("Validation: ${validateDraft(model.field.text)}", fontSize = 10.sp)
        Text("Row: ${row ?: "empty"}", fontSize = 10.sp)
        Text("Counts: ${model.saves}:${model.calls}:${model.queries}:${model.freshCalls}", fontSize = 10.sp)
        Text("Probe: ${model.probe}", fontSize = 10.sp)
    }
}
