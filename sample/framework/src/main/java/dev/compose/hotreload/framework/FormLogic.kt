package dev.compose.hotreload.framework

fun formValidation(value: String): String = "valid-v1:$value"
fun formNumberAllowed(value: CharSequence): Boolean = value.all { it.isDigit() } && value.length <= 4
fun formOutputPrefix(): String = "#"
