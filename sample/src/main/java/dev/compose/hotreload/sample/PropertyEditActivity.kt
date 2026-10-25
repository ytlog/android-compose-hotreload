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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

class PropertyEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val provider = ViewModelProvider(this)
        val evolving = provider[PropertyViewModel::class.java]
        val state = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PropertyState(evolving) as T
        })[PropertyState::class.java]
        setContent {
            MaterialTheme {
                val sentinel = remember { UUID.randomUUID().toString().take(8) }
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text("Property edits")
                    Text("Property state: ${state.token}")
                    Text("Property remember: $sentinel")
                    Text("Objects retained: ${state.retained}")
                    OutlinedTextField(value = state.draft, onValueChange = { state.draft = it }, label = { Text("Property draft") })
                    Row {
                        Button(onClick = { state.seed(); focus.clearFocus(); keyboard?.hide() }, enabled = !state.seeded) { Text("Seed models") }
                        Button(onClick = { state.inspect() }) { Text("Inspect models") }
                    }
                    Row {
                        Button(onClick = { state.migrate() }) { Text("Migrate delegate") }
                        Button(onClick = { state.collectGarbage() }) { Text("GC: ${state.collections}") }
                    }
                    Text("Property draft: ${state.draft}")
                    Text("Property calls: ${state.calls}")
                    state.report.forEach { Text(it, fontSize = 10.sp, lineHeight = 11.sp) }
                }
            }
        }
    }
}
