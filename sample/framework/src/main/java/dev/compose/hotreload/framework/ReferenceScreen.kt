package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp

private class ReferencePage {
    val identity = token()
    val receiver = ReferenceReceiver()
    var bound by mutableStateOf<(() -> Unit)?>(null)
    var listener by mutableStateOf<RetainedReferenceListener?>(null)
    var result by mutableStateOf("none")
    var fresh by mutableStateOf("none")
    var reads by mutableIntStateOf(0)
    fun register() {
        check(bound == null && listener == null)
        // Registration is a real UI event. Editing a future factory must not recreate old registrations.
        bound = receiver::fire
        listener = referenceListener(receiver)
    }
}

object ReferenceAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
}

@Composable
fun ReferenceScreen() {
    val page = remember { ReferencePage() }
    DisposableEffect(page) {
        ReferenceAudit.created++
        onDispose { ReferenceAudit.disposed++ }
    }
    Column {
        Text("Reference anchor: ${page.identity}:${page.receiver.identity}:${System.identityHashCode(page.bound)}:${System.identityHashCode(page.listener)}", fontSize = 10.sp)
        Text("Reference state: ${page.receiver.draft}:${page.receiver.calls}:${page.reads}", fontSize = 10.sp)
        Text("Bound result: ${page.receiver.result}", fontSize = 10.sp)
        Text("Anonymous result: ${page.result}", fontSize = 10.sp)
        Text("Fresh reference: ${page.fresh}", fontSize = 10.sp)
        if (page.bound == null) Button(onClick = { page.register() }) { Text("Register refs") }
        else {
            Button(onClick = page.bound!!) { Text("Invoke bound") }
            Button(onClick = { page.reads++; page.result = page.listener!!.read() }) { Text("Invoke anonymous") }
        }
        Button(onClick = { page.receiver.draft = "edited-receiver" }) { Text("Edit receiver") }
        Button(onClick = { page.fresh = referenceListener(page.receiver).read() }) { Text("Fresh anonymous") }
    }
}
