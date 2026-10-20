/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.tests.gradle

import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.test.gradle.ExtendGradleProperties
import org.jetbrains.compose.reload.test.gradle.GradlePropertiesExtension
import org.jetbrains.compose.reload.test.gradle.HotReloadTest
import org.jetbrains.compose.reload.test.gradle.HotReloadTestFixture
import org.jetbrains.compose.reload.test.gradle.initialSourceCode
import org.jetbrains.compose.reload.utils.QuickTest
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyToRecursively
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals

@ExtendGradleProperties(JetbrainsRuntimeBinaryPropertyTest.Extension::class)
class JetbrainsRuntimeBinaryPropertyTest {

    @TempDir
    lateinit var tempDir: Path

    val tempJavaHome: Path
        get() = tempDir.resolve("java")

    val tempJavaBinary: Path
        get() = tempJavaHome.resolve("bin/java")

    @HotReloadTest
    @QuickTest
    fun `test - jetbrains runtime binary property`(
        fixture: HotReloadTestFixture,
    ) = fixture.runTest {
        val client = fixture.initialSourceCode(
            """
            import org.jetbrains.compose.reload.test.*
            
            fun main() {
                screenshotTestApplication {
                    TestText("Before")
                }
            }
            """.trimIndent()
        ) {
            skipToMessage<OrchestrationMessage.ClientConnected> { message ->
                message.clientRole == OrchestrationClientRole.Application
            }
        }
        val clientPid = client.clientPid ?: error("Missing pid in ClientConnected message")
        assertEquals(tempJavaBinary.absolutePathString(), ProcessHandle.of(clientPid).get().info().command().get())
    }

    class Extension : GradlePropertiesExtension {
        @OptIn(ExperimentalPathApi::class)
        override fun properties(context: ExtensionContext): List<String> {
            val test = context.requiredTestInstance as JetbrainsRuntimeBinaryPropertyTest
            val currentJavaHome = Path(System.getProperty("java.home"))
            if (!currentJavaHome.isDirectory()) error("Invalid java.home: $currentJavaHome")
            currentJavaHome.copyToRecursively(test.tempJavaHome, followLinks = true, overwrite = true)
            return listOf("compose.reload.jbr.binary=${test.tempJavaBinary.absolutePathString()}")
        }
    }
}
