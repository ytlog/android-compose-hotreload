package dev.compose.hotreload.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var count by remember { mutableIntStateOf(0) }
                var draft by remember { mutableStateOf("draft42") }
                val token = remember { UUID.randomUUID().toString() }
                Column {
                    Text("Page: $token")
                    Text(Business.label(count))
                    TextField(draft, { draft = it })
                    Button(onClick = { count += Business.step() }) { Text("Increment") }
                }
            }
        }
    }
}
