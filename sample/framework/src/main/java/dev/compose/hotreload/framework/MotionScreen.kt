package dev.compose.hotreload.framework

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MotionAudit {
    val identity = token()
    var starts by mutableIntStateOf(0)
    var ends by mutableIntStateOf(0)
    var cancels by mutableIntStateOf(0)
    var frame by mutableStateOf("none")
    var pointerStarts by mutableIntStateOf(0)
    var pointerStops by mutableIntStateOf(0)
    var downs by mutableIntStateOf(0)
    var ups by mutableIntStateOf(0)
    var moves by mutableIntStateOf(0)
    var result by mutableStateOf("none")
}

@Composable
fun MotionScreen() {
    val state = remember { MotionAudit() }
    val animation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("motion-draft") }
    Column {
        Text("Motion anchor: ${state.identity}|$draft|${System.identityHashCode(animation)}", fontSize = 10.sp)
        Text("Animation audit: ${state.starts}:${state.ends}:${state.cancels}:${animation.isRunning}", fontSize = 10.sp)
        Text("Animation value: ${animation.value.toInt()}", fontSize = 10.sp)
        Text("Animation frame: ${state.frame}", fontSize = 10.sp)
        Row {
            Button(onClick = { draft = "edited-motion"; scope.launch {
                state.starts++
                try {
                    animation.animateTo(1000f, tween(180000, easing = LinearEasing)) {
                        state.frame = animationFrame(value)
                    }
                    state.ends++
                } catch (e: CancellationException) { state.cancels++; throw e }
            } }) { Text("Start animation") }
            Button(onClick = { scope.launch { animation.stop() } }) { Text("Stop animation") }
        }
        Text("Gesture audit: ${state.pointerStarts}:${state.pointerStops}:${state.downs}:${state.ups}:${state.moves}", fontSize = 10.sp)
        Text("Gesture result: ${state.result}", fontSize = 10.sp)
        Box(Modifier.fillMaxWidth().height(150.dp).background(Color.LightGray).pointerInput(Unit) {
            state.pointerStarts++
            try {
                awaitEachGesture {
                    awaitFirstDown(); state.downs++
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.positionChanged()) {
                                state.moves += 1
                                state.result = gestureLabel(state.moves)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    state.ups++
                }
            } finally { state.pointerStops++ }
        }) { Text("Drag surface") }
    }
}
