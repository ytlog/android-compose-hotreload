package dev.compose.hotreload.sample

/** A lambda inside an interface (default method) */
interface Labels {
    fun label(): String {
        val text: () -> String = { "Interface lambda" }
        return text()
    }
}

object DefaultLabels : Labels {
    fun implementationText(): String = "Implementation"
}

/** A non-composable caller, so editing interface calls does not change Counter's own restart group. */
fun labelsText(): String = DefaultLabels.label() + " / " + DefaultLabels.implementationText()
