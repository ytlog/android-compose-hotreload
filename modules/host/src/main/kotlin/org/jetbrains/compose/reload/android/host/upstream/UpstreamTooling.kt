package org.jetbrains.compose.reload.android.host.upstream

import org.jetbrains.compose.reload.agent.orchestration
import org.jetbrains.compose.reload.agent.sendAsync
import org.jetbrains.compose.reload.android.host.device.Device
import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.core.HotReloadEnvironment
import org.jetbrains.compose.reload.core.invokeOnValue
import org.jetbrains.compose.reload.core.withType
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.CleanCompositionRequest
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.RetryFailedCompositionRequest

/** The Android session exposes device events; only this adapter translates upstream tooling messages. */
internal object UpstreamTooling {
    fun start() = UpstreamAgent.start()

    fun attach(device: Device) {
        device.onMessage { message ->
            when (message) {
                is Protocol.UIRendered -> OrchestrationMessage.UIRendered(
                    windowId = null,
                    reloadRequestId = message.reloadRequestId?.let { reloadRequestIds[it] },
                    iteration = message.iteration,
                ).sendAsync()

                is Protocol.UIException -> OrchestrationMessage.UIException(
                    windowId = null,
                    message = message.message,
                    stacktrace = message.stacktrace.orEmpty().map { frame ->
                        StackTraceElement(frame.className, frame.methodName, frame.fileName, frame.lineNumber)
                    }
                ).sendAsync()

                else -> Unit
            }
        }

        /* upstream 'DevelopmentEntryPoint': both requests reset the composition */
        orchestration.messages.withType<CleanCompositionRequest>().invokeOnValue {
            device.send(Protocol.ResetComposition(HotReloadEnvironment.compositionHardResetEnabled))
        }
        orchestration.messages.withType<RetryFailedCompositionRequest>().invokeOnValue {
            device.send(Protocol.ResetComposition(HotReloadEnvironment.compositionHardResetEnabled))
        }
    }
}
