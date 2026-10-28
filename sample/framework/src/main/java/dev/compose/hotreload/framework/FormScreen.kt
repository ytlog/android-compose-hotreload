package dev.compose.hotreload.framework

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object FormAudit { var created by mutableIntStateOf(0); var disposed by mutableIntStateOf(0) }

class FormState {
    val identity = token()
    val name = TextFieldState("seed")
    val number = TextFieldState("12")
    val first = FocusRequester()
    var focused by mutableStateOf("none")
    var next by mutableIntStateOf(0)
    var submits by mutableIntStateOf(0)
    var result by mutableStateOf("none")
    val input = InputTransformation { if (!formNumberAllowed(asCharSequence())) revertAllChanges() }
    val output = OutputTransformation { insert(0, formOutputPrefix()) }
}

@Composable
fun FormScreen() {
    val page = remember { FormState() }
    val next = remember { KeyboardActionHandler { default -> page.next++; default() } }
    val done = remember { KeyboardActionHandler { default ->
        page.submits += 1
        page.result = formValidation(page.name.text.toString()) + ":" + page.number.text
        default()
    } }
    DisposableEffect(page) { FormAudit.created++; onDispose { FormAudit.disposed++ } }
    Column {
        Text("Form anchor: ${page.identity}:${System.identityHashCode(page.name)}:${System.identityHashCode(page.number)}:${System.identityHashCode(page.input)}:${System.identityHashCode(page.output)}:${System.identityHashCode(done)}", fontSize = 9.sp)
        Text("Form name: ${page.name.text}:${page.name.selection.start}:${page.name.selection.end}:${page.name.composition?.let { "${it.start}:${it.end}" } ?: "none"}", fontSize = 10.sp)
        Text("Form number: ${page.number.text}:${page.number.selection.start}:${page.number.selection.end}", fontSize = 10.sp)
        Text("Form focus: ${page.focused}", fontSize = 10.sp)
        Text("Form actions: ${page.next}:${page.submits}:${page.result}", fontSize = 10.sp)
        Text("Form validation: ${formValidation(page.name.text.toString())}", fontSize = 10.sp)
        Text("Name field")
        BasicTextField(state = page.name, lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), onKeyboardAction = next,
            modifier = Modifier.fillMaxWidth().height(48.dp).border(1.dp, Color.Gray)
                .focusRequester(page.first).onFocusChanged { if (it.isFocused) page.focused = "name" })
        Text("Number field")
        BasicTextField(state = page.number, lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = page.input, outputTransformation = page.output,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), onKeyboardAction = done,
            modifier = Modifier.fillMaxWidth().height(48.dp).border(1.dp, Color.Gray)
                .onFocusChanged { if (it.isFocused) page.focused = "number" })
        Button(onClick = { page.first.requestFocus() }) { Text("Focus form") }
    }
}
