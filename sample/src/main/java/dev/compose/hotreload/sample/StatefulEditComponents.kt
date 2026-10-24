package dev.compose.hotreload.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.util.UUID

/** State owners stay separate from edited ordering/labels: these states are unrelated to those edits. */
@Composable
fun StatefulEditPanel(name: String) {
    val token = remember { UUID.randomUUID().toString().take(8) }
    var count by remember { mutableIntStateOf(0) }
    Button(onClick = { count++ }) { Text("Panel $name:$token:$count") }
}

@Composable
fun StatefulEditRow(item: StateEditItem, model: StateEditModel) {
    val token = rememberSaveable { UUID.randomUUID().toString().take(8) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            StateEditRowLabel(item.id, token, count)
            Text("Object ${item.id}:${item.identity}")
        }
        Button(onClick = { count++ }) { Text("${item.id} +") }
        Button(onClick = { model.selected = item.id }) { Text("Open ${item.id}") }
    }
}
