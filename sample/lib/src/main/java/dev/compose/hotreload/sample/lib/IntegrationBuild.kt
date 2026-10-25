package dev.compose.hotreload.sample.lib

const val INTEGRATION_CONSTANT = "constant-v1"
inline fun integrationInline(): String = "inline-v1"
fun integrationDefault(value: String = "default-v1"): String = value
inline fun <reified T> integrationType(): String = "type-v1:${T::class.java.simpleName}"
