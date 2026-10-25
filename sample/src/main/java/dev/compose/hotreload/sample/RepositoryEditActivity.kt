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

class RepositoryEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val state = ViewModelProvider(this)[RepositoryState::class.java]
        setContent {
            MaterialTheme {
                val sentinel = remember { UUID.randomUUID().toString().take(8) }
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text("Repository edits")
                    Text("Repository state: ${state.token}")
                    Text("Repository remember: $sentinel")
                    Text("Objects retained: ${state.retained}")
                    OutlinedTextField(value = state.draft, onValueChange = { state.draft = it }, label = { Text("Repository draft") })
                    Row {
                        Button(onClick = { state.seed(); focus.clearFocus(); keyboard?.hide() }, enabled = !state.seeded) { Text("Seed repositories") }
                        Button(onClick = { state.inspect() }) { Text("Inspect repositories") }
                    }
                    Row {
                        Button(onClick = { state.collectGarbage() }) { Text("GC: ${state.collections}") }
                    }
                    Text("Repository draft: ${state.draft}")
                    Text("Repository calls: ${state.calls}")
                    state.report.forEach { Text(it, fontSize = 8.sp, lineHeight = 9.sp) }
                }
            }
        }
    }
}
