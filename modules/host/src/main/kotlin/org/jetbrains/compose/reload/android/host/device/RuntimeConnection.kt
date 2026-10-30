package org.jetbrains.compose.reload.android.host.device

import org.jetbrains.compose.reload.android.host.reload.recordReloadTiming
import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.android.protocol.IoDeadline
import org.jetbrains.compose.reload.android.protocol.TransportFailure
import org.jetbrains.compose.reload.android.protocol.TransportFailure.Stage.*
import org.jetbrains.compose.reload.android.protocol.TransportFailure.Reason.*
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.error
import java.io.Closeable
import java.io.IOException
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
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
        "Start a new hot reload session before submitting more changes. ${cause.message}", cause
)

/** Transport deadlines include writes and partial frames; idle connections have no read timeout. */
internal class RuntimeConnection private constructor(
    private val socket: Socket,
    val hello: Protocol.Hello,
    private val reloadTimeoutMillis: Long,
    private val writeTimeoutMillis: Long,
    private val frameTimeoutMillis: Long,
) : Closeable {
    private val output = socket.getOutputStream().buffered()
    private val state = Any()
    private var failure: IOException? = null
    private val pending = mutableMapOf<String, CompletableFuture<Protocol.ReloadResult>>()
    private val listeners = mutableListOf<(Protocol.Message) -> Unit>()
    // Render can precede the acknowledgement or arrive later. Diagnostic state is bounded per session.
    private val awaitingRender = linkedMapOf<String, Long>()

    // An uncertain transaction keeps the host available to report failures through orchestration.
    // It must not trigger the ordinary host shutdown path, which shuts down the application too.
    val closed = CompletableFuture<Unit>()

    init {
        thread(name = "Android Hot Reload: Device Reader", isDaemon = true) {
            try {
                val input = PushbackInputStream(socket.getInputStream().buffered(), 1)
                while (true) {
                    val first = input.read()
                    if (first < 0) throw TransportFailure(READ_FRAME, PEER_CLOSED, null, "EOF at frame boundary", null)
                    input.unread(first)
                    val message = IoDeadline.run({
                        fail(TransportFailure(READ_FRAME, TIMEOUT, null, "deadline=${frameTimeoutMillis}ms", null))
                    }, frameTimeoutMillis, "Runtime frame deadline=${frameTimeoutMillis}ms") { Protocol.read(input) }
                        ?: throw IOException("Truncated runtime frame")
                    if (message is Protocol.ReloadResult) {
                        synchronized(state) { pending.remove(message.reloadRequestId)?.complete(message) }
                    } else {
                        if (message is Protocol.UIRendered && message.reloadRequestId != null) {
                            val started = synchronized(state) { awaitingRender.remove(message.reloadRequestId) }
                            if (started != null) recordReloadTiming(message.reloadRequestId, "DISPATCH_TO_RENDER", started)
                        }
                        synchronized(listeners) { listeners.toList() }.forEach { listener ->
                            runCatching { listener(message) }.onFailure {
                                connectionLogger.error("Failed handling '${message.javaClass.simpleName}'", it)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                fail(if (error is TransportFailure) error else TransportFailure.io(READ_FRAME, null,
                    if (error is IOException) error else IOException(error)))
            }
        }
    }

    fun ensureUsable() = synchronized(state) { failure?.let { throw it } }

    fun onMessage(listener: (Protocol.Message) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    private fun fail(error: IOException) = synchronized(state) {
        if (failure != null) return@synchronized
        val contextual = if (error is TransportFailure && error.requestId == null && pending.isNotEmpty())
            error.withRequestId(pending.keys.single()) else error
        val terminal = if (pending.isNotEmpty()) ReloadOutcomeUnknownException(contextual) else contextual
        failure = terminal
        pending.values.forEach { it.completeExceptionally(terminal) }
        pending.clear()
        awaitingRender.clear()
        runCatching { socket.close() }
        if (terminal !is ReloadOutcomeUnknownException) closed.complete(Unit)
        if (contextual !is TransportFailure || contextual.reason != LOCAL_CLOSE) connectionLogger.error(contextual.message ?: "Runtime transport failed")
    }

    fun send(message: Protocol.Message) {
        require(message !is Protocol.Reload) { "Reloads require acknowledgement tracking" }
        ensureUsable()
        write(prepare(message))
    }

    private fun write(prepared: Protocol.PreparedMessage) {
        val started = System.nanoTime()
        var succeeded = false
        var finished = false
        val deadline = deadlines.schedule({
            synchronized(state) {
                if (!finished) fail(TransportFailure(WRITE_FRAME, TIMEOUT, prepared.requestId(), "deadline=${writeTimeoutMillis}ms", null))
            }
        }, writeTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            synchronized(output) {
                ensureUsable()
                prepared.writeTo(output)
            }
            ensureUsable()
            succeeded = true
        } catch (error: IOException) {
            fail(TransportFailure.io(WRITE_FRAME, prepared.requestId(), error))
            ensureUsable()
        } finally {
            synchronized(state) { finished = true }
            deadline.cancel(false)
            prepared.requestId()?.let { recordReloadTiming(it, "WRITE_FRAME", started, prepared.wireSize().toLong(), succeeded) }
        }
    }

    private fun prepare(message: Protocol.Message): Protocol.PreparedMessage = try {
        Protocol.prepare(message)
    } catch (error: IOException) {
        // Local rejection: no frame was sent, so the session remains reusable.
        throw TransportFailure(ENQUEUE, MESSAGE_REJECTED, Protocol.requestId(message), error.message, error)
    }

    fun reload(request: Protocol.Reload): Protocol.ReloadResult {
        ensureUsable()
        val prepared = prepare(request)
        val reply = CompletableFuture<Protocol.ReloadResult>()
        synchronized(state) {
            ensureUsable()
            check(pending.isEmpty()) { "A reload is already in progress" }
            pending[request.reloadRequestId] = reply
        }
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            synchronized(state) {
                awaitingRender[request.reloadRequestId] = System.nanoTime()
                if (awaitingRender.size > 64) awaitingRender.remove(awaitingRender.keys.first())
            }
            // Writes get their own deadline. Only a fully flushed frame begins the result wait.
            write(prepared)
            val waitStarted = System.nanoTime()
            deadline = deadlines.schedule({
                synchronized(state) {
                    if (pending[request.reloadRequestId] === reply) {
                        fail(TransportFailure(RELOAD, TIMEOUT, request.reloadRequestId, "result deadline=${reloadTimeoutMillis}ms", null))
                    }
                }
            }, reloadTimeoutMillis, TimeUnit.MILLISECONDS)
            var received = false
            try {
                return reply.get().also { received = true }
            } finally {
                recordReloadTiming(request.reloadRequestId, "WAIT_RESULT", waitStarted, success = received)
            }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: Exception) {
            fail(TransportFailure(RELOAD, IO_FAILURE, request.reloadRequestId, "exchange interrupted", error))
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ensureUsable()
            throw error
        } finally {
            deadline?.cancel(false)
        }
    }

    override fun close() {
        fail(TransportFailure(CLOSE, LOCAL_CLOSE, null, "host closed session", null))
        closed.complete(Unit)
    }

    companion object {
        fun connect(
            address: InetSocketAddress,
            packageName: String,
            handshakeTimeoutMillis: Long,
            reloadTimeoutMillis: Long = 120_000,
            writeTimeoutMillis: Long = 10_000,
            frameTimeoutMillis: Long = 120_000,
        ): RuntimeConnection {
            require(handshakeTimeoutMillis > 0 && reloadTimeoutMillis > 0 && writeTimeoutMillis > 0 && frameTimeoutMillis > 0)
            val socket = Socket()
            try {
                val hello = IoDeadline.run(socket, handshakeTimeoutMillis, "Runtime handshake deadline=${handshakeTimeoutMillis}ms") {
                    socket.connect(address, handshakeTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    Protocol.writeHandshake(socket.getOutputStream())
                    Protocol.readHandshake(socket.getInputStream())
                    val hello = Protocol.read(socket.getInputStream()) as? Protocol.Hello
                        ?: throw IOException("Expected runtime Hello")
                    if (hello.packageName != packageName || hello.protocolVersion != Protocol.VERSION) {
                        throw IOException("Unexpected runtime identity or protocol version")
                    }
                    hello
                }
                return RuntimeConnection(socket, hello, reloadTimeoutMillis, writeTimeoutMillis, frameTimeoutMillis)
            } catch (error: Exception) {
                socket.close()
                throw if (error is IOException) TransportFailure.io(HANDSHAKE, null, error) else error
            }
        }
    }
}
