@file:JvmName("AndroidHotReloadHost")

package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.session.ApplicationIndex
import org.jetbrains.compose.reload.android.host.device.Device
import org.jetbrains.compose.reload.android.host.upstream.UpstreamTooling
import org.jetbrains.compose.reload.android.host.upstream.launchReloadRequestHandler
import org.jetbrains.compose.reload.android.host.device.Adb
import org.jetbrains.compose.reload.android.host.dex.Dexer

import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.info
import kotlin.system.exitProcess

private val logger = createLogger()

/**
 * Takes the place of the upstream agent's 'premain' for an application running on an Android device.
 * The lifetime of this process mirrors the lifetime of the application process.
 */
fun main() {
    val config = HostConfig.load()

    /* upstream 'premain': devtools, orchestration, pid file, logs */
    UpstreamTooling.start()

    val adb = Adb(config.adb, config.serial)
    val index = ApplicationIndex(config.applicationClasspath, config.bootClasspath)
    val dexer = Dexer(config.minSdk, config.bootClasspath, config.applicationClasspath)
    val device = Device.launch(adb, config.applicationId)
    device.hello.runtimeError?.let { error ->
        logger.error("Compose Hot Reload is not available in '${config.applicationId}': $error")
    }

    /* The application is bound to this process, as upstream applications are bound to their agent */
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { device.send(Protocol.Shutdown("Compose Hot Reload host stopped")) }
        device.close()
    })

    UpstreamTooling.attach(device)
    launchReloadRequestHandler(index, dexer, device)

    device.closed.get()
    logger.info("Application '${config.applicationId}' (pid ${device.hello.pid}) exited")
    exitProcess(0)
}
