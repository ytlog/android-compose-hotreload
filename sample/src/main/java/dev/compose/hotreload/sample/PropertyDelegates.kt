package dev.compose.hotreload.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PropertyDelegates {
    var value: String by mutableStateOf("delegate-v1")
    private var warmRuns = 0
    private var coldRuns = 0
    private val warm: String by lazy { warmRuns++; "warm-v1" }
    private val cold: String by lazy { coldRuns++; "cold-v1" }
    fun lazyValues(): String = "$warm|pending|$warmRuns/$coldRuns"
    fun seedValue() { value = "delegate-edited"; check(warm == "warm-v1") }
    // ADDED_DELEGATE
    fun readValue(): String = try { value.toString() } catch (failure: ClassCastException) { "ClassCastException" }
    fun addedValue(): String = "absent"
    fun migrate() { value = "migrated" }
}
