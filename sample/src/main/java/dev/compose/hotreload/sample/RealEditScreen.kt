package dev.compose.hotreload.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
fun RealEditScreen(model: RealEditModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.padding(12.dp)) {
        Text("Real edits v1")
        Text("Model: ${model.identity}")
        Text("Listener: ${model.callbackIdentity}")
        Text("Listener retained: ${model.callbackRetained}")
        OutlinedTextField(value = model.input, onValueChange = { model.input = it }, label = { Text("Draft") })
        Row {
            Button(onClick = { model.selected = "Item A"; focus.clearFocus(); keyboard?.hide() }) { Text("Item A") }
            Button(onClick = { model.selected = "Item B"; focus.clearFocus(); keyboard?.hide() }) { Text("Item B") }
        }
        Text("Page: ${model.selected}")
        Text("Draft value: ${model.input}")
        Button(onClick = { model.applyBusinessLogic() }) { Text("Apply logic") }
        Text("Result: ${model.result}")
        Text("Operations: ${model.operations}")
        Row {
            Button(onClick = { model.invokeRegistered() }) { Text("Call listener") }
            Button(onClick = { model.registerAgain() }) { Text("Register again") }
        }
        Text("Callback: ${model.callbackResult}")
        Text("Callback calls: ${model.callbackCalls}")
        Button(onClick = { model.collectGarbage() }) { Text("GC: ${model.collections}") }
        // INSERT_COMPONENT
    }
}
