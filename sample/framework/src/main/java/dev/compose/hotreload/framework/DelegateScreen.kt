package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp

@Composable
fun DelegateScreen() {
    val page = remember { token() }
    val model = remember { DelegateModel() }
    val guard = remember { DelegateGuard::class.java.getDeclaredConstructor().newInstance() }
    var fresh by remember { mutableStateOf("none") }
    DisposableEffect(Unit) { DelegateAudit.pages++; onDispose { DelegateAudit.disposed++ } }
    Column {
        Text("Delegate anchor: $page:${model.identity}:${model.originalDelegate()}:${System.identityHashCode(guard)}", fontSize = 10.sp)
        Text("Delegate original: ${model.original}:${model.clicks}", fontSize = 10.sp)
        Text("Delegate added: ${model.added()}", fontSize = 10.sp)
        Text("Delegate created: ${DelegateAudit.created}", fontSize = 10.sp)
        Text("Delegate fresh: $fresh", fontSize = 10.sp)
        Row {
            Button(onClick = { model.original = "user-draft"; model.clicks++ }) { Text("Seed delegate") }
            Button(onClick = { model.changeAdded() }) { Text("Change added") }
            Button(onClick = { fresh = DelegateModel().added() }) { Text("Fresh delegate") }
        }
    }
}
