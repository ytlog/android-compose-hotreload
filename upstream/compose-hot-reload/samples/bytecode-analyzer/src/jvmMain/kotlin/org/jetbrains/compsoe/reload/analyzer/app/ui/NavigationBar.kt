package org.jetbrains.compsoe.reload.analyzer.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sellmair.evas.compose.composeValue
import io.sellmair.evas.compose.rememberEvasCoroutineScope
import io.sellmair.evas.update
import kotlinx.coroutines.launch
import org.jetbrains.compsoe.reload.analyzer.app.states.*
import java.nio.file.Path
import kotlin.io.path.name

val navigationBarWidth = 512.dp

@Composable
fun NavigationBar() {
    val workingDirectory = WorkingDirectoryState.composeValue()

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceDim)
            .fillMaxHeight()
            .width(navigationBarWidth)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
    ) {
        FileTreeElement(workingDirectory.directory, modifier = Modifier.wrapContentWidth())
    }
}

@Composable
fun FileTreeElement(state: Path, modifier: Modifier = Modifier) {
    val state = FileState.Key(state).composeValue() ?: return
    when (state) {
        is DirectoryFileState -> DirectoryTreeElement(state, modifier)
        is RegularFileState -> RegularFileTreeElement(state, modifier)
    }
}

@Composable
fun DirectoryTreeElement(state: DirectoryFileState, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(if (isExpanded) 0f else -90f)
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .widthIn(min = navigationBarWidth)
            .toggleable(
                role = Role.DropdownList,
                value = isExpanded,
                onValueChange = { isExpanded = it },
                interactionSource = interactionSource,
                indication = null,
            )

    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            Icon(
                Icons.Default.ArrowDropDown, contentDescription = null,
                modifier = Modifier.rotate(rotation).width(24.dp),
            )

            FileText(state.path)
        }

        if (isExpanded) {
            state.children.forEach { child ->
                FileTreeElement(child, modifier = modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
fun RegularFileTreeElement(state: RegularFileState, modifier: Modifier = Modifier) {
    val scope = rememberEvasCoroutineScope()

    Row(modifier.clickable {
        scope.launch {
            OpenedFileState.update { OpenedFileState(state.path) }
        }
    }) {
        Spacer(modifier = Modifier.width(24.dp))
        FileText(state.path)
    }
}

@Composable
fun FileText(path: Path) {
    Text(path.name, modifier = Modifier.padding(4.dp), fontSize = 16.sp)
}
