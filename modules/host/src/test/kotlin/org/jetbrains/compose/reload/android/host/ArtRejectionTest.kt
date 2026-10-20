package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.device.artRedefinitionFailure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtRejectionTest {
    @Test
    fun `log-only ART reasons belong to the current request and exclude echoed errors`() {
        val native = "W/art(123): FAILURE TO REDEFINE Lfixture/ComposeHotReload;: unavailable runtime service"
        val log = """
            W/art(123): FAILURE TO REDEFINE Lfixture/Old;: old failure
            D/ComposeHotReload(123): RedefineClasses request current
            $native
            E/ComposeHotReload(123): ART DCEVM capabilities after rejection: {"lastRejection":"FAILURE TO REDEFINE"}
            E/ComposeHotReload(123): RedefineClasses failed; reason follows "FAILURE TO REDEFINE" in the log
            D/ComposeHotReload(123): RedefineClasses request next
            W/art(123): FAILURE TO REDEFINE Lfixture/Next;: future failure
        """.trimIndent()
        assertEquals(native, artRedefinitionFailure(log, "current"))
        assertNull(artRedefinitionFailure(log, "missing"))
    }

    @Test
    fun `an earlier rejection cannot explain a later request`() {
        assertNull(artRedefinitionFailure("""
            W/art(123): FAILURE TO REDEFINE Lfixture/Old;: old failure
            D/ComposeHotReload(123): RedefineClasses request current
        """.trimIndent(), "current"))
    }
}
