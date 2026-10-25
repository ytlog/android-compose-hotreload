package dev.compose.hotreload.sample

/** Never edited by the scenarios: detects constructor/init replay, including between UI calls. */
object PropertyLedger {
    private val constructors = mutableMapOf<String, Int>()
    private val initializers = mutableMapOf<String, Int>()
    fun construct(kind: String) { constructors[kind] = (constructors[kind] ?: 0) + 1 }
    fun initialize(kind: String, value: Int): Int {
        initializers[kind] = (initializers[kind] ?: 0) + 1
        return value
    }
    fun counts(): List<Int> = listOf(constructors["vm"] ?: 0, constructors["business"] ?: 0,
        constructors["data"] ?: 0, initializers["vm"] ?: 0, initializers["business"] ?: 0)
}
