package dev.compose.hotreload.framework

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

object PermissionAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
    var deliveries by mutableIntStateOf(0)
}

@Composable
fun PermissionScreen() {
    val context = LocalContext.current
    val page = remember { token() }
    var draft by remember { mutableStateOf("permission-draft") }
    var calls by remember { mutableIntStateOf(0) }
    var launches by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("none") }
    val callback = remember { { granted: Boolean ->
        calls += 1
        PermissionAudit.deliveries++
        result = permissionLabel(granted, draft)
    } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), callback)
    DisposableEffect(Unit) {
        PermissionAudit.created++
        onDispose { PermissionAudit.disposed++ }
    }
    Column {
        Text("Permission anchor: $page:${System.identityHashCode(launcher)}:${System.identityHashCode(callback)}", fontSize = 10.sp)
        Text("Permission state: $draft:$calls:$launches", fontSize = 10.sp)
        Text("Permission result: $result", fontSize = 10.sp)
        Text("Permission granted: ${context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED}", fontSize = 10.sp)
        Button(onClick = { draft = "edited-permission-draft" }) { Text("Edit permission draft") }
        Button(onClick = { launches++; launcher.launch(Manifest.permission.CAMERA) }) { Text("Request camera permission") }
    }
}
