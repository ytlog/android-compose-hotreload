package dev.compose.hotreload.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

class CoroutineEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val state = ViewModelProvider(this)[CoroutineState::class.java]
        setContent {
            MaterialTheme {
                val sentinel = remember { UUID.randomUUID().toString().take(8) }
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text("Coroutine edits")
                    Text("Coroutine state: ${state.token}")
                    Text("Coroutine remember: $sentinel")
                    Text("Objects retained: ${state.retained}")
                    OutlinedTextField(value = state.draft, onValueChange = { state.draft = it }, label = { Text("Coroutine draft") })
                    Row {
                        Button(onClick = { focus.clearFocus(); keyboard?.hide(); state.start("named") }) { Text("Named") }
                        Button(onClick = { state.start("lambda") }) { Text("Lambda") }
                        Button(onClick = { state.start("nested") }) { Text("Nested") }
                    }
                    Row {
                        Button(onClick = { state.start("guarded") }) { Text("Guarded") }
                        Button(onClick = { state.ledger.release() }) { Text("Reply") }
                        Button(onClick = { state.ledger.release(true) }) { Text("Fail") }
                    }
                    Row {
                        Button(onClick = { focus.clearFocus(); keyboard?.hide() }) { Text("Ready") }
                        Button(onClick = { state.cancelTask() }) { Text("Cancel task") }
                        Button(onClick = { state.collectGarbage() }) { Text("GC: ${state.collections}") }
                    }
                    Row {
                        Button(onClick = { state.start("cold-old") }) { Text("Cold old") }
                        Button(onClick = { state.start("cold-new") }) { Text("Cold new") }
                        Button(onClick = { state.collect("event") }) { Text("Collect") }
                    }
                    Row {
                        Button(onClick = { state.collect("gated") }) { Text("Gated") }
                        Button(onClick = { state.collect("state") }) { Text("State") }
                        Button(onClick = { state.emitEvent() }) { Text("Emit") }
                    }
                    Row {
                        Button(onClick = { state.cancelCollector() }) { Text("Stop flows") }
                        Button(onClick = { state.emitState() }) { Text("State value") }
                    }
                    Text("Coroutine draft: ${state.draft}")
                    state.report().forEach { Text(it, fontSize = 8.sp, lineHeight = 8.sp, maxLines = if (it.startsWith("Trace:")) 1 else 2) }
                }
            }
        }
    }
}
