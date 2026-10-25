package dev.compose.hotreload.sample

import androidx.lifecycle.ViewModel

class PropertyViewModel : ViewModel() {
    var seed: String = "vm-v1"
    var count: Int = 5
    val initialized: Int = PropertyLedger.initialize("vm", 11)
    var blockValue: Int = 0
    var reference: Any? = "reference-v1"
    val computed: String get() = "$seed/$count/getter-v1"
    fun seedFrom(input: String) { seed = input; count = 41; reference = StringBuilder("retained-ref") }
    // ADDED_PROPERTIES
    init {
        PropertyLedger.construct("vm")
        blockValue = 31
    }
    fun values(): String = "$seed|$count|$initialized|$blockValue|$computed|$reference"
}
