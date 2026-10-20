package org.jetbrains.compsoe.reload.analyzer.app.states

import io.sellmair.evas.State
import java.nio.file.Path

data class OpenedFileState(val path: Path) : State {
    companion object Key : State.Key<OpenedFileState?> {
        override val default: OpenedFileState? = null
    }
}
