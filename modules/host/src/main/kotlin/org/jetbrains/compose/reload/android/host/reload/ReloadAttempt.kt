package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.MutableApplicationInfo
import org.jetbrains.compose.reload.android.host.session.ApplicationIndex
import org.jetbrains.compose.reload.android.host.upstream.UpstreamAnalysis
import org.jetbrains.compose.reload.core.Context

/**
 * Analysis belongs to one device transaction. Discarding a failed attempt leaves the running index intact.
 * Previously applied statics-only definitions remain pending until a successful attempt commits them.
 */
internal class ReloadAttempt(private val index: ApplicationIndex) {
    val pending = MutableApplicationInfo().apply {
        index.pendingRedefinitions.classIndex.values.forEach { add(it) }
    }

    fun enqueue(classId: ClassId, bytecode: ByteArray) {
        index.enqueueRedefinition(classId, bytecode, pending)
    }

    fun analyze(context: Context) = UpstreamAnalysis.analyze(context, index.currentApplicationInfo, pending)

    /** Call only after the device confirms that its existing class definitions were applied. */
    fun commit() {
        pending.classIndex.values.toList().forEach { info ->
            index.currentApplicationInfo.remove(info.classId)
            index.currentApplicationInfo.add(info)
        }
        index.pendingRedefinitions.clear()
    }
}
