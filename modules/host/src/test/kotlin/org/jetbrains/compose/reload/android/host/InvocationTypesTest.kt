package org.jetbrains.compose.reload.android.host

import javassist.ClassPool
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.android.host.reload.InvocationTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvocationTypesTest {
    @Test
    fun `exclude impossible callback argument types without excluding interface intersections`() {
        val types = InvocationTypes(ClassPool(true))
        fun overlap(a: String, b: String) = types.mayOverlap(ClassId(a), ClassId(b))
        assertFalse(overlap("java/lang/String", "java/lang/Integer"))
        assertFalse(overlap("java/lang/Integer", "java/lang/Runnable"))
        assertTrue(overlap("java/lang/String", "java/lang/CharSequence"))
        assertTrue(overlap("java/lang/Object", "java/lang/String"))
        assertTrue(overlap("java/lang/Runnable", "java/io/Closeable"))
        assertTrue(overlap("unavailable/Type", "java/lang/String"))
        val closeable = requireNotNull(types.declarations(ClassId("java/io/Closeable")))
        assertTrue(closeable.any { it.methodName == "close" })
        assertFalse(closeable.any { it.methodName == "consumeSource" })
    }
}
