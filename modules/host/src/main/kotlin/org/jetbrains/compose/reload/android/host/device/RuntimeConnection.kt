package org.jetbrains.compose.reload.android.host.device

import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.error
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val connectionLogger = createLogger()
private val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "Android Hot Reload: Connection Deadlines").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

/** Losing an acknowledgement does not imply that ART rolled the transaction back. */
internal class ReloadOutcomeUnknownException(cause: Throwable) : IOException(
    "Reload outcome is unknown: the runtime may already have applied the change. " +
        "The application is left running, and further requests on this session are disabled. " +
        "Start a new hot reload session before submitting more changes.", cause
)

/** Transport deadlines include writes and partial frames; idle connections have no read timeout. */
internal class RuntimeConnection private constructor(
    private val socket: Socket,
    val hello: Protocol.Hello,
    private val reloadTimeoutMillis: Long,
    private val writeTimeoutMillis: Long,
) : Closeable {
    private val output = socket.getOutputStream().buffered()
    private val state = Any()
    private var failure: IOException? = null
    private val pending = mutableMapOf<String, CompletableFuture<Protocol.ReloadResult>>()
    private val listeners = mutableListOf<(Protocol.Message) -> Unit>()

    // An uncertain transaction keeps the host available to report failures through orchestration.
    // It must not trigger the ordinary host shutdown path, which shuts down the application too.
    val closed = CompletableFuture<Unit>()

    init {
        thread(name = "Android Hot Reload: Device Reader", isDaemon = true) {
            try {
                val input = socket.getInputStream().buffered()
                while (true) {
                    val message = Protocol.read(input) ?: throw IOException("Runtime connection closed")
                    if (message is Protocol.ReloadResult) {
                        synchronized(state) { pending.remove(message.reloadRequestId)?.complete(message) }
                    } else {
                        synchronized(listeners) { listeners.toList() }.forEach { listener ->
                            runCatching { listener(message) }.onFailure {
                                connectionLogger.error("Failed handling '${message.javaClass.simpleName}'", it)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                fail(IOException("Runtime connection lost", error))
            }
        }
    }

    fun ensureUsable() = synchronized(state) { failure?.let { throw it } }

    fun onMessage(listener: (Protocol.Message) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    private fun fail(error: IOException) = synchronized(state) {
        if (failure != null) return@synchronized
        val terminal = if (pending.isNotEmpty()) ReloadOutcomeUnknownException(error) else error
        failure = terminal
        pending.values.forEach { it.completeExceptionally(terminal) }
        pending.clear()
        runCatching { socket.close() }
        if (terminal !is ReloadOutcomeUnknownException) closed.complete(Unit)
    }

    fun send(message: Protocol.Message) {
        require(message !is Protocol.Reload) { "Reloads require acknowledgement tracking" }
        ensureUsable()
        var finished = false
        val deadline = deadlines.schedule({
            synchronized(state) {
                if (!finished) fail(SocketTimeoutException("Runtime write timed out"))
            }
        }, writeTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            synchronized(output) {
                ensureUsable()
                Protocol.write(output, message)
            }
            ensureUsable()
        } catch (error: IOException) {
            fail(error)
            ensureUsable()
        } finally {
            synchronized(state) { finished = true }
            deadline.cancel(false)
        }
    }

    fun reload(request: Protocol.Reload): Protocol.ReloadResult {
        val reply = CompletableFuture<Protocol.ReloadResult>()
        synchronized(state) {
            ensureUsable()
            check(pending.isEmpty()) { "A reload is already in progress" }
            pending[request.reloadRequestId] = reply
        }
        val deadline = deadlines.schedule({
            synchronized(state) {
                if (pending[request.reloadRequestId] === reply) {
                    fail(SocketTimeoutException("Reload '${request.reloadRequestId}' timed out"))
                }
            }
        }, reloadTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            synchronized(output) {
                ensureUsable()
                Protocol.write(output, request)
            }
            return reply.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: Exception) {
            fail(IOException("Reload exchange interrupted", error))
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ensureUsable()
            throw error
        } finally {
            deadline.cancel(false)
        }
    }

    override fun close() {
        fail(IOException("Runtime connection closed by host"))
        closed.complete(Unit)
    }

    companion object {
        fun connect(
            address: InetSocketAddress,
            packageName: String,
            handshakeTimeoutMillis: Long,
            reloadTimeoutMillis: Long = 120_000,
            writeTimeoutMillis: Long = 10_000,
        ): RuntimeConnection {
            require(handshakeTimeoutMillis > 0 && reloadTimeoutMillis > 0 && writeTimeoutMillis > 0)
            val socket = Socket()
            val gate = Any()
            var finished = false
            var expired = false
            val deadline = deadlines.schedule({
                synchronized(gate) {
                    if (!finished) {
                        expired = true
                        socket.close()
                    }
                }
            }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS)
            try {
                socket.connect(address, handshakeTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                Protocol.writeHandshake(socket.getOutputStream())
                Protocol.readHandshake(socket.getInputStream())
                val hello = Protocol.read(socket.getInputStream()) as? Protocol.Hello
                    ?: throw IOException("Expected runtime Hello")
                if (hello.packageName != packageName || hello.protocolVersion != Protocol.VERSION) {
                    throw IOException("Unexpected runtime identity or protocol version")
                }
                synchronized(gate) {
                    if (expired) throw SocketTimeoutException("Runtime handshake timed out")
                    finished = true
                }
                return RuntimeConnection(socket, hello, reloadTimeoutMillis, writeTimeoutMillis)
            } catch (error: Exception) {
                socket.close()
                synchronized(gate) {
                    if (expired) throw SocketTimeoutException("Runtime handshake timed out").apply { initCause(error) }
                }
                throw error
            } finally {
                synchronized(gate) { finished = true }
                deadline.cancel(false)
            }
        }
    }
}
