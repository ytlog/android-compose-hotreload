package dev.compose.hotreload.sample

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@Composable
fun IntegrationEffects(state: IntegrationState) {
    val latest by rememberUpdatedState(state.callbackValue)
    val scope = rememberCoroutineScope()
    DisposableEffect(state.effectKey) {
        val id = state.register { listenerResult(latest) }
        onDispose { state.unregister(id) }
    }
    LaunchedEffect(state.effectKey) {
        val id = state.begin("effect")
        try { state.output(id, effectResult(state.await(id))) }
        finally { state.end(id) }
    }
    Button(onClick = { scope.launch {
        val id = state.begin("scope")
        try { awaitCancellation() } finally { state.end(id) }
    } }) { Text("Scope task") }
}
