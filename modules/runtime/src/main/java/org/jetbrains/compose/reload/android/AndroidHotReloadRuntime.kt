package org.jetbrains.compose.reload.android

import android.content.ContentProvider
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Process
import android.util.Log
import org.jetbrains.compose.reload.android.protocol.Protocol
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

internal const val TAG = "ComposeHotReload"

/**
 * In-process part of Android hot reload: the counterpart of the upstream agent's 'premain'.
 * Listens on an abstract unix socket; the host (connected via 'adb forward') sends the work to execute.
 */
internal object AndroidHotReloadRuntime {
    private const val SHELL_UID = 2000
    private const val ROOT_UID = 0

    @Volatile
    private var connection: OutputStream? = null
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true

        val application = context.applicationContext
        val loader = application.classLoader

        val runtimeError = runCatching {
            check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "The application must be debuggable from process start"
            }
            JvmtiAgent.attach(application)
        }.exceptionOrNull()
        runtimeError?.let { Log.e(TAG, "Compose Hot Reload is not available", it) }

        /* upstream 'ComposeTransformer': enable Compose hot reload mode once the Compose runtime is available */
        runOnUiThreadBlocking {
            enableComposeHotReloadMode(loader)
            ResourceHotReload.initialize(application as Application)
        }

        val server = LocalServerSocket("${application.packageName}.compose-hot-reload")
        thread(name = "Compose Hot Reload", isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Compose Hot Reload server stopped", e)
                    break
                }
                try {
                    serve(socket, application, runtimeError?.toString())
                } catch (t: Throwable) {
                    Log.w(TAG, "Connection closed: $t")
                } finally {
                    connection = null
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun serve(socket: LocalSocket, application: Context, runtimeError: String?) {
        /* Code can only be submitted by the application itself, adb (shell) or root */
        val uid = socket.peerCredentials.uid
        if (uid != Process.myUid() && uid != SHELL_UID && uid != ROOT_UID) {
            Log.w(TAG, "Rejected connection from uid $uid")
            return
        }

        val input = socket.inputStream.buffered()
        val output = socket.outputStream.buffered()
        Protocol.readHandshake(input)
        Protocol.writeHandshake(output)
        synchronized(output) {
            Protocol.write(output, Protocol.Hello(Protocol.VERSION, Process.myPid(), application.packageName, runtimeError))
        }
        connection = output
        Log.i(TAG, "Compose Hot Reload host connected")

        while (true) {
            when (val message = Protocol.read(input) ?: return) {
                is Protocol.Reload -> {
                    val result = if (runtimeError != null) {
                        Protocol.ReloadResult(message.reloadRequestId, false, false, false, runtimeError, null)
                    } else {
                        executeReload(application.classLoader, application.codeCacheDir, message)
                    }
                    send(result)
                }

                is Protocol.ResetComposition -> resetComposition(application.classLoader, message.hardReset)

                is Protocol.Shutdown -> {
                    Log.i(TAG, "Received shutdown request '${message.reason}'")
                    Process.killProcess(Process.myPid())
                }

                else -> Log.w(TAG, "Unexpected message: ${message.javaClass.simpleName}")
            }
        }
    }

    /** Sends to the host, if connected */
    fun send(message: Protocol.Message) {
        val output = connection ?: return
        try {
            synchronized(output) { Protocol.write(output, message) }
        } catch (e: IOException) {
            Log.w(TAG, "Failed sending '${message.javaClass.simpleName}': $e")
        }
    }
}

/** Starts the runtime before 'Application.onCreate'. Only packaged into debuggable variants. */
internal class AndroidHotReloadInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        AndroidHotReloadRuntime.start(requireNotNull(context))
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
