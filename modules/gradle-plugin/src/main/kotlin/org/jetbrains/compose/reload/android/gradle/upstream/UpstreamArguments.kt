package org.jetbrains.compose.reload.android.gradle.upstream

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.jetbrains.compose.reload.android.gradle.AndroidHotRun
import org.jetbrains.compose.reload.core.HotReloadProperty
import org.jetbrains.compose.reload.gradle.withComposeHotReload

internal val Project.devToolsEnabled: Boolean
    get() = providers.gradleProperty(HotReloadProperty.DevToolsEnabled.key).orNull?.toBoolean() ?: true

internal val Project.automaticReloadEnabled: Provider<Boolean>
    get() = providers.gradleProperty(HotReloadProperty.GradleBuildContinuous.key).map { it.toBoolean() }.orElse(false)

internal fun AndroidHotRun.configureUpstreamArguments(pidFile: Provider<RegularFile>, reloadTaskName: String) {
    withComposeHotReload {
        /* The host is not an instrumented application: no agent */
        setAgentJar(project.files())
        setPidFile(pidFile.map { it.asFile })
        setReloadTaskName(reloadTaskName)
        isAutoRecompileEnabled(isAutoReloadEnabled)
        /* There is no application window for the devtools to attach to */
        setDevToolsDetached(
            project.providers.gradleProperty(HotReloadProperty.DevToolsDetached.key).map { it.toBoolean() }.orElse(true)
        )
    }
}
