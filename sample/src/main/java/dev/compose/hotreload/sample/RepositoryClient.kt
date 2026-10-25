package dev.compose.hotreload.sample

class RepositoryClient(val repository: EditRepository) {
    init { RepositoryLedger.clients++ }
    fun inspect(query: String): String = "client-v1/" + repositoryValues(repository, query)
}
