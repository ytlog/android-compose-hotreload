package dev.compose.hotreload.framework

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp

object ContentAudit {
    var pages by mutableIntStateOf(0); var disposed by mutableIntStateOf(0)
    var children by mutableIntStateOf(0); var removed by mutableIntStateOf(0)
    var visibleCreated by mutableIntStateOf(0); var visibleRemoved by mutableIntStateOf(0)
}
class ContentSlot {
    val identity = token()
    var clicks by mutableIntStateOf(0)
    var rendered by mutableStateOf("")
}
@Composable
fun ContentScreen() {
    val page = remember { token() }
    val slots = remember { mutableStateMapOf<Int, ContentSlot>() }
    var visibleSlot by remember { mutableStateOf<ContentSlot?>(null) }
    var draft by remember { mutableStateOf("content-draft") }
    var target by remember { mutableIntStateOf(0) }
    var exitDuration by remember { mutableIntStateOf(600000) }
    val visibility = remember { MutableTransitionState(true) }
    val transition = updateTransition(target, label = "fixture-content")
    DisposableEffect(Unit) { ContentAudit.pages++; onDispose { ContentAudit.disposed++ } }
    Column {
        Text("Content anchor: $page:${System.identityHashCode(transition)}:${System.identityHashCode(visibility)}", fontSize = 10.sp)
        Text("Content draft: $draft", fontSize = 10.sp)
        Text("Content transition: ${transition.currentState}:${transition.targetState}:${transition.isRunning}", fontSize = 10.sp)
        Text("Content lifetime: ${ContentAudit.children}:${ContentAudit.removed}", fontSize = 10.sp)
        Text("Visibility state: ${visibility.currentState}:${visibility.targetState}:${visibility.isIdle}", fontSize = 10.sp)
        Text("Visibility lifetime: ${ContentAudit.visibleCreated}:${ContentAudit.visibleRemoved}", fontSize = 10.sp)
        // Departing/overlapping content may be absent from the accessibility tree.
        // Observe the actual remembered child objects registered by their effects.
        slots.toSortedMap().forEach { (value, slot) ->
            Text("Content child $value: ${slot.identity}:${slot.clicks}", fontSize = 10.sp)
            Text("Content rendered $value: ${slot.rendered}", fontSize = 10.sp)
        }
        visibleSlot?.let { slot ->
            Text("Visible child: ${slot.identity}:${slot.clicks}", fontSize = 10.sp)
            Text("Visible rendered: ${slot.rendered}", fontSize = 10.sp)
        }
        Row {
            Button(onClick = { draft = "edited-content"; target = 1 }) { Text("Switch content") }
        }
        transition.AnimatedContent(transitionSpec = {
            fadeIn(tween(600000)) togetherWith fadeOut(tween(600000))
        }) { value ->
            val slot = remember { ContentSlot() }
            val rendered = contentLabel(value)
            SideEffect { slot.rendered = rendered }
            DisposableEffect(slot) {
                slots[value] = slot; ContentAudit.children++
                onDispose { check(slots.remove(value) === slot); ContentAudit.removed++ }
            }
            Column {
                Text("Content body $value: ${slot.identity}:${slot.clicks}", fontSize = 10.sp)
                Text(rendered, fontSize = 10.sp)
                Button(onClick = { slot.clicks += 1 }) { Text("Click content $value") }
            }
        }
        Row {
            Button(onClick = { visibility.targetState = false }) { Text("Hide content") }
            Button(onClick = { visibility.targetState = true }) { Text("Cancel hide") }
            Button(onClick = { exitDuration = 300 }) { Text("Settle hide") }
        }
        AnimatedVisibility(visibleState = visibility, enter = fadeIn(tween(300)), exit = fadeOut(tween(exitDuration))) {
            val slot = remember { ContentSlot() }
            val rendered = visibilityLabel()
            SideEffect { slot.rendered = rendered }
            DisposableEffect(slot) {
                visibleSlot = slot; ContentAudit.visibleCreated++
                onDispose { check(visibleSlot === slot); visibleSlot = null; ContentAudit.visibleRemoved++ }
            }
            Column {
                Text("Visible body: ${slot.identity}:${slot.clicks}", fontSize = 10.sp)
                Text(rendered, fontSize = 10.sp)
                Button(onClick = { slot.clicks++ }) { Text("Click visible") }
            }
        }
    }
}
