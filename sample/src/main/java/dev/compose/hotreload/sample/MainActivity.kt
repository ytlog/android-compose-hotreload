package dev.compose.hotreload.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.toArgb
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
            ResourceCard()
        }
    }
}

@Composable
fun ResourceCard() {
    val context = LocalContext.current
    val icon = painterResource(R.drawable.resource_icon)
    val bitmap = painterResource(R.drawable.resource_bitmap)
    Image(icon, contentDescription = "Resource icon")
    Text(stringResource(R.string.resource_message))
    Text(stringResource(R.string.qualified_message))
    Text("Color: ${colorResource(R.color.resource_color).toArgb().toUInt().toString(16)}")
    Text("Drawable: ${icon.intrinsicSize.width / context.resources.displayMetrics.density}")
    Text("Bitmap: ${bitmap.intrinsicSize.width}")
    Text(context.resources.openRawResource(R.raw.resource_text).bufferedReader().use { it.readText().trim() })
    Text(context.assets.open("resource_text.txt").bufferedReader().use { it.readText().trim() })
    val rawFd = context.resources.openRawResourceFd(R.raw.resource_text).use {
        it.createInputStream().bufferedReader().use { stream -> stream.readText().trim() }
    }
    val assetFd = context.assets.openFd("resource_text.txt").use {
        it.createInputStream().bufferedReader().use { stream -> stream.readText().trim() }
    }
    Text("FD: $rawFd / $assetFd")
}
