package org.jetbrains.compose.reload.android.host.tooling

import org.jetbrains.compose.reload.core.invokeOnValue
import org.jetbrains.compose.reload.core.getOrThrow
import org.jetbrains.compose.reload.orchestration.*

fun main(args: Array<String>) {
    val client = OrchestrationClient(OrchestrationClientRole.Tooling, args[0].toInt()).connectBlocking().getOrThrow()
    client.messages.invokeOnValue { message ->
        when (message) {
            is OrchestrationMessage.ReloadClassesResult -> println("ReloadClassesResult success=${message.isSuccess} reason=${message.errorMessage}")
            is OrchestrationMessage.UIException -> println("MESSAGE UIException: ${message.message}")
            else -> println("MESSAGE ${message.javaClass.simpleName}: $message")
        }
        System.out.flush()
    }
    println("PROBE_READY")
    System.out.flush()
    try {
        generateSequence(::readLine).forEach { command ->
            when (command) {
                "clean" -> client.sendBlocking(OrchestrationMessage.CleanCompositionRequest()).getOrThrow()
                "retry" -> client.sendBlocking(OrchestrationMessage.RetryFailedCompositionRequest()).getOrThrow()
                "quit" -> return
            }
        }
    } finally { client.close() }
}
