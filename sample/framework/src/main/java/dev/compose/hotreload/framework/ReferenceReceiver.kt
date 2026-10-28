package dev.compose.hotreload.framework

import androidx.compose.runtime.*

class ReferenceReceiver {
    val identity = token()
    var draft by mutableStateOf("receiver-seed")
    var calls by mutableIntStateOf(0)
    var result by mutableStateOf("none")
    fun fire() {
        calls += 1
        result = "bound-v1:$draft:$calls"
    }
}
