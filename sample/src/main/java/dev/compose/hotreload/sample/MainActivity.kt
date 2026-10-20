package dev.compose.hotreload.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.compose.hotreload.sample.lib.LibraryCard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Counter()
            }
        }
    }
}

/** Changing this value re-initializes the statics of 'MainActivityKt' (upstream 'staticsReinitializeMode') */
val subtitle = "Edit this file, then run './gradlew reload'"

fun greeting(): String = "Hello Android Hot Reload"

@Composable
fun Counter() {
    var count by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(greeting(), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { count++ }) { Text("Count: $count") }
            Spacer(Modifier.height(16.dp))
            Text(subtitle)
            Spacer(Modifier.height(16.dp))
            LibraryCard()
            Text(JavaGreeting.text())
            Text(labelsText())
        }
    }
}
