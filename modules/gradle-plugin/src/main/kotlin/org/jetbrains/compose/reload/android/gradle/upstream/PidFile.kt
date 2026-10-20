package org.jetbrains.compose.reload.android.gradle.upstream

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.compose.reload.core.PidFileInfo
import org.jetbrains.compose.reload.core.getOrThrow
import kotlin.io.path.isRegularFile

/** Same as upstream 'pidFileOrchestrationPort' (compilationExtensions.kt) */
internal fun Project.pidFileOrchestrationPort(pidFile: Provider<RegularFile>): Provider<Int> =
    providers.of(PidFileOrchestrationPort::class.java) { spec ->
        spec.parameters { params -> params.pidFile.set(pidFile) }
    }

internal abstract class PidFileOrchestrationPort : ValueSource<Int, PidFileOrchestrationPort.Params> {
    interface Params : ValueSourceParameters {
        val pidFile: RegularFileProperty
    }

    override fun obtain(): Int? {
        val pidFile = parameters.pidFile.asFile.orNull?.toPath() ?: return null
        if (pidFile.isRegularFile()) {
            return PidFileInfo(pidFile).getOrThrow().orchestrationPort
        }
        return null
    }
}
