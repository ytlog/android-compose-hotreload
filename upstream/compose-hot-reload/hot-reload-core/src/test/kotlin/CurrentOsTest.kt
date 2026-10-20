import org.jetbrains.compose.reload.core.Os
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

class CurrentOsTest {
    @Test
    fun currentOsIsAvailable() {
        assertEquals(Os.current(), Os.currentOrNull())
        assertNotNull(Os.currentOrNull())
    }
}
