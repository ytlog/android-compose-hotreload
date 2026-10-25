package dev.compose.hotreload.sample

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*

@Composable
fun IntegrationSlots() {
    var count by remember { mutableIntStateOf(10) }
    // EXTRA_SLOT
    Button(onClick = { count++ }) { Text("Slot count: $count") }
}
