package dev.compose.hotreload.framework

interface RetainedReferenceListener { fun read(): String }

fun referenceListener(receiver: ReferenceReceiver): RetainedReferenceListener {
    val prefix = "captured:${receiver.draft}"
    return object : RetainedReferenceListener {
        override fun read(): String = "anonymous-v1:$prefix:${receiver.draft}"
    }
}
