package dev.compose.hotreload.framework

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

private class FixtureDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        // The fixture exercises Android's SAF provider UI, independently of OEM file-manager defaults.
        .setPackage("com.google.android.documentsui")
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
            "dev.compose.hotreload.framework.documents", "fixture"))
}
object LauncherFixtureAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
    var deliveries by mutableIntStateOf(0)
}

@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    val page = remember { token() }
    var draft by remember { mutableStateOf("launcher-draft") }
    var calls by remember { mutableIntStateOf(0) }
    var launches by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("none") }
    val callback = remember { { uri: Uri? ->
        calls += 1
        LauncherFixtureAudit.deliveries++
        val payload = if (uri == null) "cancel" else {
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)!!
                "${bitmap.width}x${bitmap.height}:${bitmap.getPixel(0, 0)}".also { bitmap.recycle() }
            }
        }
        result = launcherLabel("$payload:$draft")
    } }
    val contract = remember { FixtureDocumentContract() }
    val launcher = rememberLauncherForActivityResult(contract, callback)
    DisposableEffect(Unit) {
        LauncherFixtureAudit.created++
        onDispose { LauncherFixtureAudit.disposed++ }
    }
    Column {
        Text("Launcher anchor: $page:${System.identityHashCode(launcher)}:${System.identityHashCode(callback)}", fontSize = 10.sp)
        Text("Launcher state: $draft:$calls:$launches", fontSize = 10.sp)
        Text("Launcher result: $result", fontSize = 10.sp)
        Button(onClick = { draft = "edited-launcher-draft" }) { Text("Edit launcher draft") }
        Button(onClick = { launches++; launcher.launch(arrayOf("image/png")) }) { Text("Pick document") }
    }
}
