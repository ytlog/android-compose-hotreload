package dev.compose.hotreload.sample

class EditRepositoryImpl(val origin: String = "direct-v1") : EditRepository {
    var value = origin
    init { RepositoryLedger.constructs++ }
    override fun read(query: String): String {
        RepositoryLedger.reads++
        require(query != "fail") { "repository-failure" }
        return "impl-v1/$value/$query"
    }
    // IMPLEMENTATION_METHODS
}
