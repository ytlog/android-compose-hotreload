package dev.compose.hotreload.framework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp

object ScopedLayoutAudit { var parents by mutableIntStateOf(0); var removed by mutableIntStateOf(0) }
fun layoutEnabled(): Boolean = true
@Composable
fun ScopedLayoutScreen() {
    val parent = remember { token() }
    var draft by remember { mutableStateOf("layout-draft") }
    DisposableEffect(Unit) { ScopedLayoutAudit.parents++; onDispose { ScopedLayoutAudit.removed++ } }
    Column {
        Text("Layout parent: $parent:$draft", fontSize = 10.sp)
        Button(onClick = { draft = "edited-layout" }) { Text("Seed layout") }
        LayoutSibling()
        if (layoutEnabled()) {
            val child = remember { token() }
            var count by remember { mutableIntStateOf(0) }
            Text("Layout child: $child:$count", fontSize = 10.sp)
            Button(onClick = { count++ }) { Text("Click layout child") }
            // EXTRA_SLOT
        }
    }
}
@Composable
fun LayoutSibling() {
    val sibling = remember { token() }
    var clicks by remember { mutableIntStateOf(0) }
    Text("Layout sibling: $sibling:$clicks", fontSize = 10.sp)
    Button(onClick = { clicks++ }) { Text("Click layout sibling") }
}
