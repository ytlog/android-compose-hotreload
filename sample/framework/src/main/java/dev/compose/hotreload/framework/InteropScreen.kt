package dev.compose.hotreload.framework

import android.content.Intent
import android.widget.Button as NativeButton
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

object InteropAudit {
    var factories by mutableIntStateOf(0)
    var releases by mutableIntStateOf(0)
    var registrations by mutableIntStateOf(0)
    var unregistrations by mutableIntStateOf(0)
    var children by mutableIntStateOf(0)
    var destroyed by mutableIntStateOf(0)
    var clean by mutableStateOf(true)
}

fun interopAuditSummary(): String = with(InteropAudit) {
    "$factories:$releases:$registrations:$unregistrations:$children:$destroyed:$clean"
}

class InteropState {
    val identity = token()
    val click = nativeClick(this)
    val watcher = nativeWatcher(this)
    val result = retainedResult(this)
    lateinit var container: LinearLayout
    lateinit var button: NativeButton
    lateinit var editor: EditText
    lateinit var launcher: ActivityResultLauncher<Intent>
    var clicks by mutableIntStateOf(0)
    var watches by mutableIntStateOf(0)
    var results by mutableIntStateOf(0)
    var nativeProbe by mutableStateOf("none")
    var watchProbe by mutableStateOf("none")
    var resultProbe by mutableStateOf("none")
    var anchors by mutableStateOf(false)

    fun release(view: LinearLayout) {
        check(view === container)
        val before = clicks to watches
        button.setOnClickListener(null)
        editor.removeTextChangedListener(watcher)
        // Exercise the detached objects, so cleanup has observable behavior.
        button.performClick()
        editor.setText("released")
        InteropAudit.clean = InteropAudit.clean && before == (clicks to watches)
        InteropAudit.releases++
    }
}

@Composable
fun InteropScreen() {
    val activity = LocalContext.current as ComponentActivity
    val state = remember { InteropState() }
    DisposableEffect(state) {
        // Explicit registry ownership: valid without a LifecycleOwner, with mandatory unregister.
        // Registration is outside update/recomposition; the registry retains the original callback.
        state.launcher = activity.activityResultRegistry.register(
            "interop-${state.identity}", ActivityResultContracts.StartActivityForResult(), state.result)
        InteropAudit.registrations++
        onDispose {
            state.launcher.unregister()
            InteropAudit.unregistrations++
        }
    }
    Column {
        Text("Interop: ${state.identity}", fontSize = 10.sp)
        AndroidView(factory = { context ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                state.container = this
                state.editor = EditText(context).apply {
                    setText("native-draft")
                    setSelection(2, 5)
                    showSoftInputOnFocus = false
                    addTextChangedListener(state.watcher)
                }
                state.button = NativeButton(context).apply {
                    tag = token()
                    setOnClickListener(state.click)
                }
                addView(state.button)
                addView(state.editor)
                InteropAudit.factories++
            }
        }, onRelease = { state.release(it) }, update = { view ->
            state.button.text = nativeLabel()
            state.editor.contentDescription = nativeUpdateNote()
            state.anchors = view === state.container && view.getChildAt(0) === state.button &&
                view.getChildAt(1) === state.editor
        }, modifier = Modifier.fillMaxWidth().height(120.dp))
        // Read native state on demand, independently of the Compose model.
        var nativeSnapshot by remember { mutableStateOf("none") }
        Row {
            Button(onClick = {
                nativeSnapshot = "${state.button.tag}:${state.editor.text}:${state.editor.selectionStart}:${state.editor.selectionEnd}:${state.editor.contentDescription}"
            }) { Text("Inspect native") }
            Button(onClick = { state.editor.setText("edited-draft"); state.editor.setSelection(1, 4) }) { Text("Write native") }
        }
        Button(onClick = {
            state.launcher.launch(Intent(activity, InteropResultActivity::class.java))
        }) { Text("Launch result") }
        Text("Native snapshot: $nativeSnapshot", fontSize = 10.sp)
        Text("Native anchors: ${state.anchors}", fontSize = 10.sp)
        Text("Interop counts: ${state.clicks}:${state.watches}:${state.results}", fontSize = 10.sp)
        Text("Native probe: ${state.nativeProbe}", fontSize = 10.sp)
        Text("Watch probe: ${state.watchProbe}", fontSize = 10.sp)
        Text("Result probe: ${state.resultProbe}", fontSize = 10.sp)
        Text("Interop audit: ${interopAuditSummary()}", fontSize = 10.sp)
    }
}
