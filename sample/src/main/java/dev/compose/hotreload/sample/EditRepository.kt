package dev.compose.hotreload.sample

interface EditRepository {
    fun read(query: String): String
    fun summary(query: String): String = "default-v1/${read(query)}"
    fun detail(): String = "detail-v1"
    // REPOSITORY_METHODS
}
