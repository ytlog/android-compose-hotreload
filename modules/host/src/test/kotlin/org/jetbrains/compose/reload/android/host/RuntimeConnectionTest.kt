package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.device.ReloadOutcomeUnknownException
import org.jetbrains.compose.reload.android.host.device.RuntimeConnection
import org.jetbrains.compose.reload.android.protocol.Protocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeConnectionTest {
    private fun request(id: String = "first") = Protocol.Reload(id, emptyList(), emptyList(), intArrayOf())
    private fun result(id: String, success: Boolean = true) =
        Protocol.ReloadResult(id, success, false, true, if (success) null else "rejected", null)

    private class Peer(val serve: (Socket) -> Unit) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = CompletableFuture<Socket>()
        val done = CompletableFuture<Unit>()
        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        accepted.complete(socket)
                        serve(socket)
                    }
                    done.complete(Unit)
                } catch (error: Throwable) { done.completeExceptionally(error) }
            }
        }
        fun connect(handshake: Long = 2_000, reload: Long = 2_000) = RuntimeConnection.connect(
            InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), "fixture.app", handshake, reload
        )
        override fun close() {
            accepted.getNow(null)?.close()
            server.close()
        }
    }

    private fun hello(socket: Socket) {
        Protocol.readHandshake(socket.getInputStream())
        Protocol.writeHandshake(socket.getOutputStream())
        Protocol.write(socket.getOutputStream(), Protocol.Hello(Protocol.VERSION, 123, "fixture.app", null))
    }

    @Test
    fun `silent peer cannot stall handshake`() {
        Peer { socket -> Protocol.readHandshake(socket.getInputStream()); assertEquals(-1, socket.getInputStream().read()) }.use {
            assertFailsWith<SocketTimeoutException> { it.connect(handshake = 150) }
            it.done.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `partial Hello is covered by the same handshake deadline`() {
        Peer { socket ->
            Protocol.readHandshake(socket.getInputStream())
            Protocol.writeHandshake(socket.getOutputStream())
            socket.getOutputStream().write(byteArrayOf(0, 0))
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }.use {
            assertFailsWith<SocketTimeoutException> { it.connect(handshake = 150) }
            it.done.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `wrong runtime identity cannot establish session`() {
        Peer { socket ->
            Protocol.readHandshake(socket.getInputStream())
            Protocol.writeHandshake(socket.getOutputStream())
            Protocol.write(socket.getOutputStream(), Protocol.Hello(Protocol.VERSION, 123, "other.app", null))
        }.use { assertFailsWith<IOException> { it.connect() } }
    }

    @Test
    fun `missing acknowledgement disables replay and shutdown without killing application`() {
        val replyLate = CompletableFuture<Unit>()
        Peer { socket ->
            hello(socket)
            assertEquals("first", (Protocol.read(socket.getInputStream()) as Protocol.Reload).reloadRequestId)
            replyLate.get(5, TimeUnit.SECONDS)
            runCatching { Protocol.write(socket.getOutputStream(), result("first")) }
            // A write after the host closed may produce TCP reset instead of graceful EOF.
            val next = try { Protocol.read(socket.getInputStream()) } catch (_: SocketException) { null }
            assertEquals(null, next) // no replay or Shutdown frame
        }.use { peer ->
            peer.connect(reload = 150).use { connection ->
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request()) }
                replyLate.complete(Unit)
                peer.done.get(5, TimeUnit.SECONDS)
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request("second")) }
                assertFailsWith<ReloadOutcomeUnknownException> { connection.send(Protocol.ResetComposition(true)) }
                assertFailsWith<ReloadOutcomeUnknownException> { connection.send(Protocol.Shutdown("host stopped")) }
                assertFalse(connection.closed.isDone, "Leave orchestration available to report the uncertain result")
            }
        }
    }

    @Test
    fun `disconnect while reloading is an unknown outcome`() {
        Peer { socket -> hello(socket); Protocol.read(socket.getInputStream()) }.use { peer ->
            peer.connect().use { connection ->
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request()) }
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request("second")) }
            }
        }
    }

    @Test
    fun `explicit rejection allows next transaction and idle time does not expire session`() {
        val finish = CompletableFuture<Unit>()
        Peer { socket ->
            hello(socket)
            val first = Protocol.read(socket.getInputStream()) as Protocol.Reload
            Protocol.write(socket.getOutputStream(), result(first.reloadRequestId, false))
            val second = Protocol.read(socket.getInputStream()) as Protocol.Reload
            // An unrelated/late ID must not complete the current transaction.
            Protocol.write(socket.getOutputStream(), result("unrelated", false))
            Protocol.write(socket.getOutputStream(), result(second.reloadRequestId))
            finish.get(5, TimeUnit.SECONDS)
        }.use { peer ->
            peer.connect(handshake = 500, reload = 500).use { connection ->
                assertFalse(connection.reload(request()).success)
                Thread.sleep(650)
                assertTrue(connection.reload(request("second")).success)
                connection.ensureUsable()
                finish.complete(Unit)
            }
        }
    }

    @Test
    fun `partial result cannot extend reload deadline`() {
        Peer { socket ->
            hello(socket)
            Protocol.read(socket.getInputStream())
            val bytes = ByteArrayOutputStream().also { Protocol.write(it, result("first")) }.toByteArray()
            socket.getOutputStream().write(bytes, 0, bytes.size - 1)
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }.use { peer ->
            peer.connect(reload = 150).use { connection ->
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request()) }
            }
            peer.done.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `blocked reload write is bounded and cannot be replayed`() {
        val finish = CompletableFuture<Unit>()
        Peer { socket ->
            socket.receiveBufferSize = 1024
            hello(socket)
            finish.get(5, TimeUnit.SECONDS) // never read the large request
        }.use { peer ->
            peer.connect(reload = 150).use { connection ->
                val large = Protocol.Reload("large", emptyList(), emptyList(), intArrayOf(), ByteArray(16 * 1024 * 1024))
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(large) }
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request()) }
                finish.complete(Unit)
            }
        }
    }
}
