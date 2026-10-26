package dev.compose.hotreload.framework

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.*

object ReactiveAudit {
    var active: ReactiveState? = null
    var disposed by mutableIntStateOf(0)
}

class ReactiveState {
    val identity = token()
    var field by mutableStateOf(TextFieldValue("reactive-draft", TextRange(2, 5)))
    var input by mutableIntStateOf(0)
    var secondary by mutableIntStateOf(0)
    var alternate by mutableStateOf(false)
    val source = MutableStateFlow(0)
    val snapshots = mutableStateListOf<String>()
    var snapshotStarts by mutableIntStateOf(0)
    var snapshotStops by mutableIntStateOf(0)
    var starts by mutableIntStateOf(0)
    var stops by mutableIntStateOf(0)
    val deliveries = mutableStateListOf<String>()
    fun event(value: String) { Log.i("ReactiveLedger", "$identity:$value") }
    fun audit() = "$snapshotStarts:$snapshotStops:$starts:$stops:${snapshots.size}:${deliveries.size}"
}

@Composable
fun ReactiveScreen() {
    val context = LocalContext.current
    val state = remember { ReactiveState() }
    val derived = remember { derivedStateOf { derivedProjection(state.input) } }
    val observed = remember(state) {
        state.source.onStart { state.starts++; state.event("start:${state.starts}") }
            .map { lifecycleProjection(it) }
            .onEach { state.deliveries.add(it); state.event("delivery:$it") }
            .onCompletion { state.stops++; state.event("stop:${state.stops}") }
    }
    val lifecycleValue by observed.collectAsStateWithLifecycle(initialValue = "loading")
    LaunchedEffect(state) {
        state.snapshotStarts++
        try {
            snapshotFlow { snapshotProjection(if (state.alternate) state.secondary else state.input) }
                .collect { state.snapshots.add(snapshotDelivery(it)) }
        } finally { state.snapshotStops++; state.event("snapshot-stop:${state.snapshotStops}") }
    }
    DisposableEffect(state) {
        ReactiveAudit.active = state
        onDispose {
            if (ReactiveAudit.active === state) ReactiveAudit.active = null
            ReactiveAudit.disposed++
        }
    }
    Column {
        Text("Reactive: ${state.identity}", fontSize = 10.sp)
        TextField(state.field, { state.field = it }, label = { Text("Reactive draft") }, modifier = Modifier.fillMaxWidth())
        Row {
            Button(onClick = { state.input++ }) { Text("Input plus") }
            Button(onClick = { state.secondary++ }) { Text("Alt plus") }
            Button(onClick = { state.alternate = !state.alternate }) { Text("Switch") }
        }
        Row {
            Button(onClick = { state.source.value++ }) { Text("Emit") }
            Button(onClick = { context.startActivity(Intent(context, ReactivePauseActivity::class.java)) }) { Text("Pause collector") }
        }
        Text("Reactive draft: ${state.field.text}:${state.field.selection.start}:${state.field.selection.end}", fontSize = 10.sp)
        Text("Reactive input: ${state.input}:${state.secondary}:${state.alternate}", fontSize = 10.sp)
        Text("Derived: ${derived.value}", fontSize = 10.sp)
        Text("Derived identity: ${System.identityHashCode(derived)}", fontSize = 10.sp)
        Text("Snapshots: ${state.snapshots.joinToString(";")}", fontSize = 10.sp)
        Text("Lifecycle value: $lifecycleValue", fontSize = 10.sp)
        Text("Deliveries: ${state.deliveries.joinToString(";")}", fontSize = 10.sp)
        Text("Reactive audit: ${state.audit()}", fontSize = 10.sp)
    }
}
