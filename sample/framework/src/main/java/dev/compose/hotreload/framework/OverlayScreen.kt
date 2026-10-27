package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayScreen() {
    val page = remember { token() }
    var draft by remember { mutableStateOf(TextFieldValue("draft", TextRange(1, 3))) }
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var calls by remember { mutableIntStateOf(0) }
    var dismissals by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("none") }
    val confirm = remember { { calls += 1; result = overlayLabel(draft.text) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = remember { { kind: String ->
        dismissals += 1; result = overlayDismiss(kind); dialog = false; sheet = false
    } }
    val anchor = "$page|${draft.text}:${draft.selection.start}:${draft.selection.end}|$calls:$dismissals"
    Column {
        Text("Overlay anchor: $anchor", fontSize = 10.sp)
        Text("Overlay result: $result", fontSize = 10.sp)
        TextField(draft, { draft = it }, label = { Text("Overlay draft") })
        Button(onClick = { draft = TextFieldValue("edited-draft", TextRange(2, 7)) }) { Text("Edit overlay draft") }
        Button(onClick = { dialog = true }) { Text("Show dialog") }
        Button(onClick = { sheet = true }) { Text("Show sheet") }
    }
    if (dialog) {
        val identity = remember { token() }
        var local by remember { mutableIntStateOf(0) }
        AlertDialog(onDismissRequest = { dismiss("dialog") },
            title = { Text("Dialog open") },
            text = { Column {
                Text("Overlay anchor: $anchor", fontSize = 10.sp)
                Text("Overlay result: $result", fontSize = 10.sp)
                Text("Dialog state: $identity:$local", fontSize = 10.sp)
                Text(overlayLabel(draft.text))
                Button(onClick = { local++ }) { Text("Dialog local") }
            } },
            confirmButton = { Button(onClick = confirm) { Text("Dialog apply") } },
            dismissButton = { Button(onClick = { dismiss("dialog") }) { Text("Dialog close") } })
    }
    if (sheet) {
        val identity = remember { token() }
        var local by remember { mutableIntStateOf(0) }
        ModalBottomSheet(onDismissRequest = { dismiss("sheet") }, sheetState = sheetState) {
            Text("Sheet open")
            Text("Overlay anchor: $anchor", fontSize = 10.sp)
            Text("Overlay result: $result", fontSize = 10.sp)
            Text("Sheet state: $identity:$local:${System.identityHashCode(sheetState)}:${sheetState.currentValue}", fontSize = 10.sp)
            Text(overlayLabel(draft.text))
            Button(onClick = { local++ }) { Text("Sheet local") }
            Button(onClick = confirm) { Text("Sheet apply") }
            Button(onClick = { dismiss("sheet") }) { Text("Sheet close") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
