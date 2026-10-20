package org.jetbrains.compose.reload.android.host.upstream

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.ClassInfo
import org.jetbrains.compose.reload.analysis.MutableApplicationInfo
import org.jetbrains.compose.reload.analysis.ResolvedDirtyScopes
import org.jetbrains.compose.reload.analysis.isIgnored
import org.jetbrains.compose.reload.analysis.resolveDirtyScopes
import org.jetbrains.compose.reload.analysis.verifyRedefinitions
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.Context

/** Compatibility boundary for the upstream runtime-tracking filters and dirty-scope analysis. */
internal object UpstreamAnalysis {
    private val logger = createLogger()

    fun track(classId: ClassId, bytecode: ByteArray): ClassInfo? {
        if (classId.value.startsWith("kotlin/") || classId.value.startsWith("java/") ||
            classId.value.startsWith("org/jetbrains/compose/reload") || classId.isIgnored) return null
        return try {
            ClassInfo(bytecode)
        } catch (failure: Throwable) {
            logger.error("Failed parsing 'ClassInfo' for '$classId'", failure)
            null
        }
    }

    fun analyze(context: Context, current: MutableApplicationInfo, pending: MutableApplicationInfo): ResolvedDirtyScopes {
        current.verifyRedefinitions(pending)
        return context.resolveDirtyScopes(current, pending)
    }
}
