package dev.compose.hotreload.sample

import dev.compose.hotreload.sample.lib.INTEGRATION_CONSTANT
import dev.compose.hotreload.sample.lib.integrationInline
import dev.compose.hotreload.sample.lib.integrationDefault
import dev.compose.hotreload.sample.lib.integrationType

fun effectResult(value: String): String = "effect-v1:$value"
fun listenerResult(value: String): String = "listener-v1:$value"
fun networkResult(value: String): String = "network-v1:$value"
fun sessionResult(): String = "session-v1"
fun integrationBuildResult(): String = "$INTEGRATION_CONSTANT/${integrationInline()}/${integrationDefault()}/${integrationType<String>()}"
fun integrationBatchResult(): String = "batch-v1"
