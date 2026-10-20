package org.jetbrains.compose.reload.android.host.upstream

import org.jetbrains.compose.reload.agent.orchestration
import org.jetbrains.compose.reload.agent.sendAsync
import org.jetbrains.compose.reload.analysis.ResolvedDirtyScopes
import org.jetbrains.compose.reload.analysis.ScopeType
import org.jetbrains.compose.reload.android.host.device.Device
import org.jetbrains.compose.reload.android.host.dex.Dexer
import org.jetbrains.compose.reload.android.host.reload.ClassChange
import org.jetbrains.compose.reload.android.host.reload.reload
import org.jetbrains.compose.reload.android.host.session.ApplicationIndex
import org.jetbrains.compose.reload.android.host.session.ResourceIndex
import org.jetbrains.compose.reload.core.Context
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.debug
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.exception
import org.jetbrains.compose.reload.core.isFailure
import org.jetbrains.compose.reload.core.isSuccess
import org.jetbrains.compose.reload.core.launchTask
import org.jetbrains.compose.reload.core.with
import org.jetbrains.compose.reload.core.withLinearClosure
import org.jetbrains.compose.reload.core.withType
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ReloadClassesRequest
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.InvalidatedComposeGroupMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.InvalidatedComposeGroupMessage.DirtyScope
import org.jetbrains.compose.reload.orchestration.OrchestrationMessageId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.InvalidatedComposeGroupMessage.DirtyScope.ScopeType as DirtyScopeType

private val logger = createLogger()

/**
 * Port of the upstream agent's 'reloadRequestHandler.kt':
 * Requests are handled one after another; changes of failed requests are kept and retried with the next request.
 */
internal fun launchReloadRequestHandler(index: ApplicationIndex, dexer: Dexer, device: Device, resources: ResourceIndex) = launchTask {
    var pendingChanges = mapOf<File, ClassChange>()

    orchestration.messages.withType<ReloadClassesRequest>().collect { request ->
        pendingChanges = pendingChanges + request.changedClassFiles.mapValues { (_, change) -> ClassChange.valueOf(change.name) }

        val context = Context(ReloadClassesRequest with request)
        val result = context.reload(index, dexer, device, pendingChanges, resources) { deviceRequestId ->
            reloadRequestIds[deviceRequestId] = request.messageId
        }

        if (result.isSuccess()) {
            logger.debug("Reloaded classes: ${request.messageId}")
            pendingChanges = emptyMap()
            OrchestrationMessage.ReloadClassesResult(request.messageId, true).sendAsync()
            sendInvalidatedComposeGroups(result.value)
        }

        if (result.isFailure()) {
            logger.error("Reload failed", result.exception)
            val message = result.exception.message ?: result.exception.javaClass.simpleName
            OrchestrationMessage.ReloadClassesResult(
                request.messageId, false, message,
                result.exception.withLinearClosure { throwable -> throwable.cause }
                    .flatMap { throwable -> throwable.stackTrace.toList() }
            ).sendAsync()
        }
    }
}

/** Orchestration ids of reload requests, keyed by the (opaque) id sent to the device */
internal val reloadRequestIds = ConcurrentHashMap<String, OrchestrationMessageId>()

/** Port of the upstream agent's 'compose.kt' after-reload listener */
private fun sendInvalidatedComposeGroups(dirty: ResolvedDirtyScopes) {
    val invalidations = dirty.dirtyScopes
        .filter { scope -> scope.group != null }
        .groupBy { it.group }

    invalidations.forEach { (group, scopes) ->
        if (group == null) return@forEach

        val dirtyScopes = scopes.map { scope ->
            val scopeType = when (scope.scopeType) {
                ScopeType.Method -> DirtyScopeType.Method
                ScopeType.RestartGroup -> DirtyScopeType.RestartGroup
                ScopeType.ReplaceGroup -> DirtyScopeType.ReplaceGroup
                ScopeType.SourceInformationMarker -> DirtyScopeType.SourceInformationMarker
            }
            DirtyScope(
                scopeType = scopeType,
                sourceFile = scope.sourceLocation.sourceFile,
                classId = scope.methodId.classId.value,
                methodName = scope.methodId.methodName,
                methodDescriptor = scope.methodId.methodDescriptor,
                firstLineNumber = scope.sourceLocation.firstLineNumber,
            )
        }

        logger.debug("Invalidating group '${group.key}'")
        InvalidatedComposeGroupMessage(groupKey = group.key, dirtyScopes = dirtyScopes).sendAsync()
    }
}
