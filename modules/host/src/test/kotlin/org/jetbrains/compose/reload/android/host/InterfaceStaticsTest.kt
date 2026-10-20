package org.jetbrains.compose.reload.android.host

import org.jetbrains.compose.reload.android.host.upstream.UpstreamAgent

import javassist.ClassPool
import javassist.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("unused")
interface InterfaceStaticsFixture {
    companion object {
        const val CONSTANT = "constant"
        val text = "companion"
    }
}

class InterfaceStaticsTest {
    @Test
    fun `upstream leaves interface initializers and final fields intact`() {
        val fixture = InterfaceStaticsFixture::class.java
        val code = fixture.getResourceAsStream("InterfaceStaticsFixture.class")!!.readBytes()
        val clazz = ClassPool(true).makeClass(code.inputStream())
        try {
            assertNotNull(clazz.classInitializer)
            UpstreamAgent.transformForStaticsInitialization(clazz)
            assertTrue(clazz.declaredFields.all { Modifier.isFinal(it.modifiers) })
            assertTrue(clazz.classFile.methods.none { it.name == "\$chr\$clinit" })
        } finally {
            clazz.detach()
        }
    }

    @Test
    fun `upstream transforms companion storage as an ordinary class`() {
        val code = InterfaceStaticsFixture.Companion::class.java
            .getResourceAsStream("InterfaceStaticsFixture\$Companion.class")!!.readBytes()
        val clazz = ClassPool(true).makeClass(code.inputStream())
        try {
            assertNotNull(clazz.classInitializer)
            UpstreamAgent.transformForStaticsInitialization(clazz)
            assertEquals(1, clazz.classFile.methods.count { it.name == "\$chr\$clinit" })
        } finally {
            clazz.detach()
        }
    }
}
