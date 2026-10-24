package dev.compose.hotreload.sample

fun processEdit(input: String): String = if (input.isBlank()) "empty-v1" else "business-v1:${input.trim()}"

/** Registered once by the ViewModel, so a render cannot silently replace the listener under test. */
fun registerEditCallback(model: RealEditModel): () -> String {
    // INSERT_CALLBACK
    return { "callback-v1:${model.input}" }
}
