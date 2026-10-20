package org.jetbrains.compose.reload.android.gradle.upstream

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.compose.reload.gradle.ComposeHotReloadTask
import org.jetbrains.compose.reload.gradle.ComposeHotSnapshotTask

internal class UpstreamReloadTasks(val snapshot: TaskProvider<*>, val reload: TaskProvider<*>)

internal fun Project.registerUpstreamReloadTasks(
    name: String,
    variantName: String,
    runDirectory: Provider<Directory>,
    pidFile: Provider<RegularFile>,
    hotFiles: FileCollection,
    reloadLifecycleTask: TaskProvider<*>,
): UpstreamReloadTasks {
    /* upstream 'hotSnapshotTask' */
    val snapshotTask = tasks.register("hotSnapshot$name", ComposeHotSnapshotTask::class.java) { task ->
        task.description = "Takes a snapshot of the classpath of the '$variantName' variant"
        task.classpath.from(hotFiles)
        task.classpathSnapshotFile.set(runDirectory.map { it.file("classpath/.snapshot") })
        task.pendingRequestFile.set(runDirectory.map { it.file("classpath/.request") })
        task.classesDirectory.set(runDirectory.map { it.dir("classpath/hot") })
    }

    /* upstream 'hotReloadTask' */
    val reloadTask = tasks.register("hotReload$name", ComposeHotReloadTask::class.java) { task ->
        task.group = "Compose Hot Reload"
        task.description = "Hot Reloads code associated with the '$variantName' variant"
        task.onlyIf("Running application is known") { task.agentPort.isPresent }
        task.agentPort.set(pidFileOrchestrationPort(pidFile))
        task.pendingRequestFile.set(snapshotTask.flatMap { it.pendingRequestFile })
        task.dependsOn(snapshotTask)
    }

    reloadLifecycleTask.configure { task ->
        task.dependsOn(tasks.withType(ComposeHotReloadTask::class.java).matching { it.agentPort.isPresent })
    }

    return UpstreamReloadTasks(snapshotTask, reloadTask)
}
