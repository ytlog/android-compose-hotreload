package dev.compose.hotreload.framework
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(val dao: DraftDao) {
    fun evaluate(text: String): String = "business-v1:$text"
}

fun registeredAction(repository: DraftRepository, draft: () -> String): () -> String =
    { "callback-v1:${repository.evaluate(draft())}" }

fun validateDraft(text: String): String = "valid-v1:${text.length}"
