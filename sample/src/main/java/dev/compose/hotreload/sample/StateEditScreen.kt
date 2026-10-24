package dev.compose.hotreload.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun StateEditScreen(model: StateEditModel, listState: LazyListState) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val items = model.items()
    Column(Modifier.padding(8.dp)) {
        Text("Development edits")
        Text("State model: ${model.identity}")
        Text("Service: ${model.serviceIdentity}")
        Text("Service retained: ${model.serviceRetained}")
        OutlinedTextField(value = model.draft, onValueChange = { model.draft = it }, label = { Text("List draft") })
        Button(onClick = { focus.clearFocus(); keyboard?.hide() }) { Text("Finish input") }
        Text("Draft: ${model.draft}")
        Text("Detail: ${model.selection()}")
        Text("Anchor: ${items.getOrNull(listState.firstVisibleItemIndex)?.id}:${listState.firstVisibleItemIndex}:${listState.firstVisibleItemScrollOffset}")
        sectionOrder().forEach { section ->
            key(section) {
                when (section) {
                    "panels" -> Row {
                        panelOrder().forEach { name -> key(name) { StatefulEditPanel(name) } }
                    }
                    "list" -> LazyColumn(state = listState, modifier = Modifier.height(204.dp)
                        .semantics { contentDescription = "Dynamic list" }) {
                        items(items, key = { it.id }) { item -> StatefulEditRow(item, model) }
                    }
                }
            }
        }
        Row {
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.invokeRefactor() }) { Text("Run refactor") }
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.collectGarbage() }) { Text("GC: ${model.collections}") }
        }
        Text("Calls: ${model.calls}")
        Text("Refactor: ${model.result}")
        Text("Return type: ${model.resultType}")
    }
}
