package dev.compose.hotreload.sample

/** Not redefined: catches constructor replay, duplicate providers and proxy invocations. */
object RepositoryLedger {
    var constructs = 0
    var clients = 0
    var reads = 0
    var proxyCalls = 0
    fun counts(): List<Int> = listOf(constructs, clients, reads, proxyCalls)
}
