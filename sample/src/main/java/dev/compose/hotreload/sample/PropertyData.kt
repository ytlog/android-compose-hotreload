package dev.compose.hotreload.sample

data class PropertyData(var title: String = "data-v1", var amount: Int = 3) {
    init { PropertyLedger.construct("data") }
}
