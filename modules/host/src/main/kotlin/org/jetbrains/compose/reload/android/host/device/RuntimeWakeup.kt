package org.jetbrains.compose.reload.android.host.device

/** A provider acquisition wakes frozen background apps without launching/recreating an Activity. */
internal fun wakeRuntime(packageName: String, pid: Int, run: (List<String>) -> String) {
    val running = run(listOf("shell", "pidof", packageName)).trim().split(Regex("\\s+"))
    check(pid.toString() in running) { "The original application process is no longer running" }
    val reply = run(listOf("shell", "content", "call", "--uri", "content://$packageName.compose-hot-reload", "--method", "ping"))
    val reported = Regex("(?m)^Result: Bundle\\[\\{pid=(\\d+)\\}\\]$").find(reply.trim())?.groupValues?.get(1)?.toIntOrNull()
    check(reported == pid) { "The runtime wakeup did not confirm the original application process: $reply" }
}
