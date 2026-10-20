package org.jetbrains.compsoe.reload.analyzer.app.states

import io.sellmair.evas.State
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute

class WorkingDirectoryState(val directory: Path) : State {
    companion object Key : State.Key<WorkingDirectoryState> {
        override val default: WorkingDirectoryState = WorkingDirectoryState(Path(".").absolute())
    }
}