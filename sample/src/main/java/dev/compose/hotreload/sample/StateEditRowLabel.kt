package dev.compose.hotreload.sample

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun StateEditRowLabel(id: String, token: String, count: Int) {
    Text("Row $id:$token:$count")
}
