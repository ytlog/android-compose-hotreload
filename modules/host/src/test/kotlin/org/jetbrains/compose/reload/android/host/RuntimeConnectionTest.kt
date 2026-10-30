package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.device.ReloadOutcomeUnknownException
import org.jetbrains.compose.reload.android.host.device.RuntimeConnection
import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.android.protocol.TransportFailure
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
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
        fun connect(handshake: Long = 2_000, reload: Long = 2_000, frame: Long = 120_000, write: Long = 10_000) = RuntimeConnection.connect(
            InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), "fixture.app", handshake, reload, writeTimeoutMillis = write, frameTimeoutMillis = frame
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
            val failure = assertFailsWith<TransportFailure> { it.connect(handshake = 150) }
            assertEquals(TransportFailure.Stage.HANDSHAKE, failure.stage)
            assertEquals(TransportFailure.Reason.TIMEOUT, failure.reason)
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
            val failure = assertFailsWith<TransportFailure> { it.connect(handshake = 150) }
            assertEquals(TransportFailure.Stage.HANDSHAKE, failure.stage)
            assertEquals(TransportFailure.Reason.TIMEOUT, failure.reason)
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
            peer.connect(write = 150, reload = 2_000).use { connection ->
                val large = Protocol.Reload("large", emptyList(), emptyList(), intArrayOf(), ByteArray(16 * 1024 * 1024))
                val error = assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(large) }
                val failure = error.cause as TransportFailure
                assertEquals(TransportFailure.Stage.WRITE_FRAME, failure.stage)
                assertEquals(TransportFailure.Reason.TIMEOUT, failure.reason)
                assertEquals("large", failure.requestId)
                assertFalse(connection.closed.isDone)
                assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request()) }
                finish.complete(Unit)
            }
        }
    }

    @Test
    fun `idle is unlimited but a partial unsolicited event closes with a frame diagnostic`() {
        val send = CompletableFuture<Unit>()
        Peer { socket ->
            hello(socket)
            send.get(5, TimeUnit.SECONDS)
            socket.getOutputStream().write(0)
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }.use { peer ->
            peer.connect(frame = 150).use { connection ->
                Thread.sleep(350)
                connection.ensureUsable()
                send.complete(Unit)
                connection.closed.get(5, TimeUnit.SECONDS)
                val failure = assertFailsWith<TransportFailure> { connection.ensureUsable() }
                assertEquals(TransportFailure.Stage.READ_FRAME, failure.stage)
                assertEquals(TransportFailure.Reason.TIMEOUT, failure.reason)
                assertEquals(null, failure.requestId)
                peer.done.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `frame timeout during a reload preserves unknown outcome and request identity`() {
        Peer { socket ->
            hello(socket)
            Protocol.read(socket.getInputStream())
            socket.getOutputStream().write(0)
            socket.getOutputStream().flush()
            assertEquals(-1, socket.getInputStream().read())
        }.use { peer ->
            peer.connect(frame = 150).use { connection ->
                val error = assertFailsWith<ReloadOutcomeUnknownException> { connection.reload(request()) }
                val failure = error.cause as TransportFailure
                assertEquals(TransportFailure.Stage.READ_FRAME, failure.stage)
                assertEquals(TransportFailure.Reason.TIMEOUT, failure.reason)
                assertEquals("first", failure.requestId)
                assertFalse(connection.closed.isDone)
                peer.done.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `oversized local requests send nothing and leave the session reusable`() {
        val finish = CompletableFuture<Unit>()
        Peer { socket ->
            hello(socket)
            val request = Protocol.read(socket.getInputStream()) as Protocol.Reload
            assertEquals("small", request.reloadRequestId)
            Protocol.write(socket.getOutputStream(), result("small"))
            finish.get(5, TimeUnit.SECONDS)
        }.use { peer ->
            peer.connect().use { connection ->
                val bad = Protocol.Reload("large", emptyList(), listOf("x".repeat(Protocol.MAX_EVENT_SIZE + 1)), intArrayOf())
                val failure = assertFailsWith<TransportFailure> { connection.reload(bad) }
                assertEquals(TransportFailure.Stage.ENQUEUE, failure.stage)
                assertEquals(TransportFailure.Reason.MESSAGE_REJECTED, failure.reason)
                assertEquals("large", failure.requestId)
                assertFailsWith<TransportFailure> { connection.send(Protocol.Shutdown("x".repeat(Protocol.MAX_CONTROL_SIZE))) }
                assertTrue(connection.reload(request("small")).success)
                connection.ensureUsable()
                finish.complete(Unit)
                peer.done.get(5, TimeUnit.SECONDS)
            }
        }
    }
    @Test
    fun `flushed reload gets a separate result window and cancelled write timers cannot close it`() {
        val finish = CompletableFuture<Unit>()
        Peer { socket ->
            hello(socket)
            repeat(2) {
                val request = Protocol.read(socket.getInputStream()) as Protocol.Reload
                Thread.sleep(350) // Longer than write deadline, within the result deadline.
                Protocol.write(socket.getOutputStream(), result(request.reloadRequestId))
            }
            finish.get(5, TimeUnit.SECONDS)
        }.use { peer ->
            peer.connect(write = 150, reload = 1_500).use { connection ->
                assertTrue(connection.reload(request()).success)
                assertTrue(connection.reload(request("second")).success)
                Thread.sleep(350)
                connection.ensureUsable()
                finish.complete(Unit)
                peer.done.get(5, TimeUnit.SECONDS)
            }
        }
    }

}
