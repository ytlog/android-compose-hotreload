package org.jetbrains.compose.reload.android.host.device

import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.debug
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.info
import org.jetbrains.compose.reload.core.warn
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val logger = createLogger()

internal class Adb(private val executable: File, private val serial: String?) {
    fun run(vararg arguments: String): String {
        val command = listOf(executable.absolutePath) + (serial?.let { listOf("-s", it) } ?: emptyList()) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "adb output") { process.inputStream.use { it.copyTo(output) } }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("'adb ${arguments.joinToString(" ")}' timed out")
        }
        reader.join(1000)
        val text = output.toString(Charsets.UTF_8).trim()
        check(process.exitValue() == 0) { "'adb ${arguments.joinToString(" ")}' failed: $text" }
        return text
    }
}

/** Name of the abstract unix socket the device runtime listens on: see 'AndroidHotReloadRuntime' */
internal fun socketName(applicationId: String) = "$applicationId.compose-hot-reload"

/**
 * Connection to the device runtime of the running application.
 * Requests are sent by the host; the device answers reloads and reports UI events.
 */
internal class Device private constructor(
    private val socket: Socket,
    val hello: Protocol.Hello,
    private val adb: Adb,
) {
    private val output = socket.getOutputStream().buffered()
    private val pendingReloads = HashMap<String, CompletableFuture<Protocol.ReloadResult>>()
    private val listeners = mutableListOf<(Protocol.Message) -> Unit>()

    /** Completes once the application process is gone (or the connection is lost) */
    val closed = CompletableFuture<Unit>()

    private fun startReading() = thread(name = "Android Hot Reload: Device Reader", isDaemon = true) {
        try {
            val input = socket.getInputStream().buffered()
            while (true) {
                val message = Protocol.read(input) ?: break
                when (message) {
                    is Protocol.ReloadResult -> synchronized(pendingReloads) {
                        pendingReloads.remove(message.reloadRequestId)
                    }?.complete(message)
                    else -> synchronized(listeners) { listeners.toList() }.forEach { listener ->
                        try {
                            listener(message)
                        } catch (t: Throwable) {
                            logger.error("Failed handling '${message.javaClass.simpleName}'", t)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            logger.debug("Device connection closed: ${e.message}")
        } finally {
            val failure = IOException("The application process '${hello.packageName}' (pid ${hello.pid}) disconnected")
            synchronized(pendingReloads) { pendingReloads.values.toList().also { pendingReloads.clear() } }
                .forEach { it.completeExceptionally(failure) }
            closed.complete(Unit)
        }
    }

    fun onMessage(listener: (Protocol.Message) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun send(message: Protocol.Message) {
        synchronized(output) { Protocol.write(output, message) }
    }

    /** Blocks until the device executed the reload (like the agent blocks 'reloadMain' while reloading) */
    fun reload(reload: Protocol.Reload): Protocol.ReloadResult {
        val result = CompletableFuture<Protocol.ReloadResult>()
        synchronized(pendingReloads) { pendingReloads[reload.reloadRequestId] = result }
        send(reload)
        val reply = result.get()
        /* Policy rejections include lastRejection directly. Some ART checks only explain themselves in logcat. */
        if (!reply.success && reply.errorMessage?.contains("FAILURE TO REDEFINE") == true) {
            val detail = runCatching {
                artRedefinitionFailure(
                    adb.run("logcat", "-d", "--pid=${hello.pid}", "-v", "brief"), reload.reloadRequestId
                )
            }.onFailure { logger.warn("Could not read ART rejection from logcat: ${it.message}") }.getOrNull()
            if (detail != null) return Protocol.ReloadResult(
                reply.reloadRequestId, false, reply.classesAdded, reply.classesRedefined,
                "${reply.errorMessage}\n$detail", reply.errorStacktrace
            )
        }
        return reply
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Starts the application (fresh process) and connects to its runtime.
         * The runtime starts listening in the application process before 'Application.onCreate'.
         */
        fun launch(adb: Adb, applicationId: String): Device {
            adb.run("shell", "am", "force-stop", applicationId)
            val component = adb.run(
                "shell", "cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", applicationId
            ).lines().last().trim()
            check(component.contains('/')) { "No launcher activity found for '$applicationId': $component" }
            logger.info("Launching '$component'")
            adb.run("shell", "am", "start", "-n", component)

            val port = adb.run("forward", "tcp:0", "localabstract:${socketName(applicationId)}").trim().toInt()
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { adb.run("forward", "--remove", "tcp:$port") }
            })

            /* 'adb forward' accepts connections before the app listens: retry until the handshake succeeds */
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var lastFailure: Throwable? = null
            while (System.nanoTime() < deadline) {
                val socket = Socket(InetAddress.getLoopbackAddress(), port)
                try {
                    Protocol.writeHandshake(socket.getOutputStream())
                    Protocol.readHandshake(socket.getInputStream())
                    val hello = Protocol.read(socket.getInputStream()) as? Protocol.Hello
                        ?: throw IOException("Expected 'Hello'")
                    logger.info("Connected to '${hello.packageName}' (pid ${hello.pid})")
                    return Device(socket, hello, adb).apply { startReading() }
                } catch (t: IOException) {
                    lastFailure = t
                    socket.close()
                    Thread.sleep(250)
                }
            }
            throw IOException("Failed to connect to '$applicationId'. Is the app built with the hot reload runtime?", lastFailure)
        }
    }
}

/** Only native failures following this request's marker; never an older request or our own echoed JSON/exception. */
internal fun artRedefinitionFailure(logcat: String, requestId: String): String? {
    val marker = "RedefineClasses request $requestId"
    val start = logcat.lastIndexOf(marker)
    if (start < 0) return null
    val echoedError = Regex("^E/ComposeHotReload\\s*\\(\\s*\\d+\\):")
    return logcat.substring(start + marker.length).lineSequence()
        .takeWhile { "RedefineClasses request " !in it }
        .filter { "FAILURE TO REDEFINE" in it && !echoedError.containsMatchIn(it) }
        .joinToString("\n").takeIf { it.isNotBlank() }
}
