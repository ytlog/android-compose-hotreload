package org.jetbrains.compose.reload.android.host.device

import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.info
import org.jetbrains.compose.reload.core.warn
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val connection: RuntimeConnection,
    private val adb: Adb,
) {
    val hello: Protocol.Hello get() = connection.hello
    val closed get() = connection.closed

    fun ensureUsable() = connection.ensureUsable()

    fun onMessage(listener: (Protocol.Message) -> Unit) = connection.onMessage(listener)

    fun send(message: Protocol.Message) {
        connection.ensureUsable()
        if (message is Protocol.ResetComposition) wake()
        connection.send(message)
    }

    private fun wake() {
        wakeRuntime(hello.packageName, hello.pid) { arguments -> adb.run(*arguments.toTypedArray()) }
    }

    fun reload(reload: Protocol.Reload): Protocol.ReloadResult {
        connection.ensureUsable()
        // Wakeup fails before dispatch, so it cannot create an uncertain transaction.
        wake()
        val reply = connection.reload(reload)
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
        connection.close()
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
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMillis <= 0) break
                try {
                    val connection = RuntimeConnection.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), port), applicationId,
                        handshakeTimeoutMillis = minOf(5_000, remainingMillis),
                    )
                    logger.info("Connected to '${connection.hello.packageName}' (pid ${connection.hello.pid})")
                    return Device(connection, adb)
                } catch (t: IOException) {
                    lastFailure = t
                    val pause = minOf(250, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
                    if (pause > 0) Thread.sleep(pause)
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
