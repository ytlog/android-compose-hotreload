package dev.compose.hotreload.framework

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient

/** Real loopback HTTP and PNG decode, with an explicit gate independent of build duration. */
private class GatedImageServer : AutoCloseable {
    val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val gate = Semaphore(0)
    var requests by mutableIntStateOf(0)
    var replies by mutableIntStateOf(0)
    var waiting by mutableStateOf(false)
    @Volatile var closed = false
    private var worker: Thread? = null
    fun start() {
        check(worker == null)
        worker = thread(name = "fixture-image-server", isDaemon = true) {
        while (!closed) {
            try {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests++; waiting = true
                    gate.acquire()
                    if (closed) return@thread
                    val failed = path.contains("fail")
                    val bytes = if (failed) byteArrayOf() else fixturePng()
                    val header = "HTTP/1.1 ${if (failed) "503 Unavailable" else "200 OK"}\r\n" +
                        "Content-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                    replies++; waiting = false
                }
            } catch (error: Exception) {
                if (!closed) throw error
            }
        }
    }
    }
    override fun close() { closed = true; gate.release(); socket.close(); worker?.join(2000) }
}

fun fixturePng(): ByteArray {
    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.GREEN)
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
    }
}

object ImageFixtureAudit {
    var created by mutableIntStateOf(0)
    var disposed by mutableIntStateOf(0)
}

@Composable
fun ImageScreen() {
    val context = LocalContext.current
    val page = remember { token() }
    var draft by remember { mutableStateOf("image-draft") }
    val server = remember { GatedImageServer() }
    val loader = remember {
        ImageLoader.Builder(context).okHttpClient(
            OkHttpClient.Builder().readTimeout(15, TimeUnit.MINUTES).retryOnConnectionFailure(false).build()
        ).build()
    }
    var mode by remember { mutableStateOf("ok") }
    var shown by remember { mutableStateOf(true) }
    var successes by remember { mutableIntStateOf(0) }
    var errors by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("none") }
    val success = remember { { state: AsyncImagePainter.State.Success ->
        successes += 1
        val pixel = (state.result.drawable as BitmapDrawable).bitmap.getPixel(0, 0)
        result = imageLabel("${state.result.dataSource}:$pixel:$draft")
    } }
    val failure = remember { { _: AsyncImagePainter.State.Error -> errors++; result = imageLabel("error:$draft") } }
    val requestMode = mode
    val request = remember(requestMode) {
        ImageRequest.Builder(context).data("http://127.0.0.1:${server.socket.localPort}/$requestMode.png")
            .memoryCacheKey("$page:$requestMode").diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.ENABLED).allowHardware(false).size(64).build()
    }
    DisposableEffect(loader, server) {
        server.start()
        ImageFixtureAudit.created++
        onDispose { loader.shutdown(); server.close(); ImageFixtureAudit.disposed++ }
    }
    Column {
        Text("Image anchor: $page:${System.identityHashCode(loader)}:${System.identityHashCode(server)}:${System.identityHashCode(success)}", fontSize = 10.sp)
        Text("Image request: ${System.identityHashCode(request)}", fontSize = 10.sp)
        Text("Image draft: $draft", fontSize = 10.sp)
        Text("Image counts: $successes:$errors:${server.requests}:${server.replies}:${server.waiting}", fontSize = 10.sp)
        Text("Image result: $result", fontSize = 10.sp)
        Button(onClick = { draft = "edited-image-draft" }) { Text("Edit image draft") }
        Row {
            Button(onClick = { server.gate.release() }) { Text("Release image") }
            Button(onClick = { shown = !shown }) { Text(if (shown) "Hide image" else "Show image") }
        }
        Row {
            Button(onClick = { mode = "fail" }) { Text("Fail image") }
            Button(onClick = { mode = "retry" }) { Text("Retry image") }
        }
        if (shown) AsyncImage(model = request, imageLoader = loader, contentDescription = "Network image",
            onSuccess = success, onError = failure, modifier = Modifier.size(100.dp))
    }
}
