package org.jetbrains.compsoe.reload.analyzer.app.states

import io.sellmair.evas.State
import io.sellmair.evas.launchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed class FileState : State {
    abstract val path: Path

    data class Key(val path: Path) : State.Key<FileState?> {
        override val default: FileState? = null
    }
}

data class RegularFileState(
    override val path: Path
) : FileState()

data class DirectoryFileState(
    override val path: Path,
    val children: List<Path>
) : FileState()

fun CoroutineScope.launchFileTreeState() = launchState(
    Dispatchers.IO, keepActive = 1.minutes
) { key: FileState.Key ->
    while (true) {
        when {
            key.path.isRegularFile() -> RegularFileState(key.path).emit()
            key.path.isDirectory() -> DirectoryFileState(key.path, key.path.listDirectoryEntries()).emit()
            else -> null.emit()
        }
        delay(10.seconds)
    }
}