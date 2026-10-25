package dev.compose.hotreload.sample

class AlternateRepository(val origin: String) : EditRepository {
    init { RepositoryLedger.constructs++ }
    override fun read(query: String): String {
        RepositoryLedger.reads++
        require(query != "fail") { "repository-failure" }
        return "alternate-v1/$origin/$query"
    }
    // IMPLEMENTATION_METHODS
}
