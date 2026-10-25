package dev.compose.hotreload.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

class IntegrationEditActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastBeat = 0L
    private var maximumGap = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (lastBeat != 0L) maximumGap = maxOf(maximumGap, now - lastBeat)
            lastBeat = now; handler.postDelayed(this, 50)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val state = ViewModelProvider(this)[IntegrationState::class.java]
        setContent { MaterialTheme { CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
            val root = remember { UUID.randomUUID().toString().take(8) }
            var savedCount by rememberSaveable { mutableIntStateOf(0) }
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            BackHandler(state.route != "list") { state.back() }
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Text("Integration edits")
                Text("Integration model: ${state.token}")
                Text("Integration root: $root")
                Text("Route: ${state.route}")
                OutlinedTextField(state.draft, state::editDraft, label = { Text("Integration input") })
                Row {
                    Button(onClick = { focus.clearFocus(); keyboard?.hide() }) { Text("Ready") }
                    Button(onClick = { state.enter() }) { Text("Enter") }
                    Button(onClick = { state.back() }) { Text("Leave") }
                    Button(onClick = {
                        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(
                            if (dark) android.app.UiModeManager.MODE_NIGHT_NO else android.app.UiModeManager.MODE_NIGHT_YES)
                    }) { Text("Theme") }
                }
                Row {
                    Button(onClick = { savedCount++ }) { Text("Saved: $savedCount") }
                    Button(onClick = { recreate() }) { Text("Recreate") }
                    Button(onClick = { state.collectGarbage() }) { Text("GC: ${state.collections}") }
                }
                Row {
                    Button(onClick = { state.effectKey++ }) { Text("Next key") }
                    Button(onClick = { state.enabled = !state.enabled }) { Text("Toggle") }
                    Button(onClick = { state.callbackValue = "latest" }) { Text("Latest") }
                }
                Row {
                    Button(onClick = { state.fire() }) { Text("Fire") }
                    Button(onClick = { state.reply() }) { Text("Reply") }
                    if (state.route != "list" && state.enabled) IntegrationEffects(state)
                }
                if (state.route != "list") IntegrationSlots()
                Row {
                    Button(onClick = { state.network(false) }) { Text("HTTP old") }
                    Button(onClick = { state.network(true) }) { Text("HTTP new") }
                    Button(onClick = { state.cancelNetwork() }) { Text("Cancel HTTP") }
                }
                Row {
                    Button(onClick = { state.json(false) }) { Text("JSON old") }
                    Button(onClick = { state.json(true) }) { Text("JSON new") }
                    Button(onClick = { state.build() }) { Text("Build") }
                }
                Row {
                    Button(onClick = { state.generated(false) }) { Text("Generated old") }
                    Button(onClick = { state.generated(true) }) { Text("Generated new") }
                    Button(onClick = { state.probe = "${IntegrationInitializer.value}:${IntegrationInitLedger.calls}" }) { Text("Init") }
                }
                Row {
                    Button(onClick = { state.startWorker() }) { Text("Worker") }
                    Button(onClick = { state.stopWorker() }) { Text("Join") }
                    Button(onClick = { state.session() }) { Text("Version") }
                }
                Row {
                    Button(onClick = { state.probe = integrationBatchResult() + ":" + getString(R.string.resource_message) }) { Text("Batch") }
                    Button(onClick = { state.probe = "gap:$maximumGap"; maximumGap = 0 }) { Text("Gap") }
                }
                Text("Integration draft: ${state.draft}", fontSize = 9.sp)
                Text("Active: ${state.counts()}", fontSize = 9.sp)
                Text("Probe: ${state.probe}", fontSize = 9.sp, maxLines = 1)
                Text("Trace: ${state.events.joinToString(";")}", fontSize = 8.sp, maxLines = 1)
            }
        } } }
    }
    override fun onResume() { super.onResume(); lastBeat = 0; handler.post(heartbeat) }
    override fun onPause() { handler.removeCallbacks(heartbeat); super.onPause() }
}
