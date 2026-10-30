package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.upstream.toToolingMessage
import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolingMessageTest {
    @Test
    fun `device render replies preserve official request identity without registration`() {
        val request = OrchestrationMessage.ReloadClassesRequest(emptyMap())
        val wireId = request.messageId.encodeToByteArray().decodeToString()
        val bytes = ByteArrayOutputStream().also { Protocol.write(it, Protocol.UIRendered(wireId, 7)) }.toByteArray()
        val response = Protocol.read(ByteArrayInputStream(bytes)).toToolingMessage() as OrchestrationMessage.UIRendered
        assertEquals(request.messageId, response.reloadRequestId)
        assertEquals(7, response.iteration)
        // A delayed event still resolves after arbitrary newer IDs without an ever-growing map.
        repeat(1_000) {
            Protocol.UIRendered("$it\$later", 1).toToolingMessage()
        }
        assertEquals(request.messageId, (Protocol.UIRendered(wireId, 8).toToolingMessage() as OrchestrationMessage.UIRendered).reloadRequestId)
    }

    @Test
    fun `startup render and device exception retain upstream semantics`() {
        assertNull((Protocol.UIRendered(null, 0).toToolingMessage() as OrchestrationMessage.UIRendered).reloadRequestId)
        val error = Protocol.UIException("fixture failure", listOf(Protocol.StackFrame("Fixture", "render", "Fixture.kt", 12)))
            .toToolingMessage() as OrchestrationMessage.UIException
        assertEquals("fixture failure", error.message)
        assertEquals(StackTraceElement("Fixture", "render", "Fixture.kt", 12), error.stacktrace.single())
        assertNull(Protocol.ReloadResult("done", true, false, true, null, null).toToolingMessage())
    }
}
