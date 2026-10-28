package dev.compose.hotreload.framework

import androidx.compose.runtime.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Real HTTP, held at the server until the UI releases exactly one response. */
class RemoteFeedServer : AutoCloseable {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port get() = socket.localPort
    private val gate = Semaphore(0)
    var requests by mutableIntStateOf(0)
    var replies by mutableIntStateOf(0)
    var offsets by mutableStateOf("")
    var pending by mutableIntStateOf(-1)
    var failNext by mutableStateOf(false)
    @Volatile private var closed = false
    private var worker: Thread? = null
    fun start() {
        check(worker == null)
        worker = thread(name = "fixture-feed-http", isDaemon = true) {
            while (!closed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val offset = path.substringAfter("offset=").toInt()
                        val failed = failNext; failNext = false
                        requests++; pending = offset
                        offsets += (if (offsets.isEmpty()) "" else ",") + offset
                        gate.acquire()
                        if (closed) return@thread
                        val size = if (offset == 0) 20 else 10
                        val bytes = (offset until offset + size).joinToString(",").toByteArray()
                        val header = "HTTP/1.1 ${if (failed) "503 Unavailable" else "200 OK"}\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                        replies++; pending = -1
                    }
                } catch (error: Exception) {
                    if (!closed) throw error
                }
            }
        }
    }
    fun release() { check(pending >= 0); gate.release() }
    override fun close() {
        closed = true; gate.release(); socket.close(); worker?.join(2000)
        check(worker?.isAlive != true)
    }
}
