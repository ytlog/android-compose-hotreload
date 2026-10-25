package dev.compose.hotreload.sample

inline fun repositoryAttempt(block: () -> String): String = try {
    block()
} catch (failure: Throwable) {
    failure.javaClass.simpleName
}

fun repositoryValues(repository: EditRepository, query: String): String =
    "${repository.read(query)}|${repository.summary(query)}|${repository.detail()}" // PROBE_METHODS
