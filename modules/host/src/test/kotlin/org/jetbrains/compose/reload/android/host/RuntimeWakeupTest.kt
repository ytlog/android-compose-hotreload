package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.device.wakeRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeWakeupTest {
    @Test
    fun `wake the original process through its restricted provider`() {
        val commands = mutableListOf<List<String>>()
        wakeRuntime("fixture.app", 123) { command ->
            commands += command
            if (command[1] == "pidof") "123" else "Result: Bundle[{pid=123}]"
        }
        assertEquals(listOf(
            listOf("shell", "pidof", "fixture.app"),
            listOf("shell", "content", "call", "--uri", "content://fixture.app.compose-hot-reload", "--method", "ping"),
        ), commands)
    }

    @Test
    fun `do not acquire a provider after original process death`() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            wakeRuntime("fixture.app", 123) { calls++; "456" }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `reject process replacement and provider errors without pretending wakeup succeeded`() {
        for (reply in listOf("Result: Bundle[{pid=456}]", "Error while accessing provider: pid=123", "Result: null")) {
            assertFailsWith<IllegalStateException> {
                wakeRuntime("fixture.app", 123) { if (it[1] == "pidof") "123" else reply }
            }
        }
    }
}
