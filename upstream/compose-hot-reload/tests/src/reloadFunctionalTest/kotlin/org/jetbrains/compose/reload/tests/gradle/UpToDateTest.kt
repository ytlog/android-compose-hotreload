/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.tests.gradle

import kotlinx.coroutines.flow.filterIsInstance
import org.jetbrains.compose.reload.core.asTemplateOrThrow
import org.jetbrains.compose.reload.core.renderOrThrow
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ClientDisconnected
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ShutdownRequest
import org.jetbrains.compose.reload.orchestration.asFlow
import org.jetbrains.compose.reload.test.gradle.BuildGradleKts
import org.jetbrains.compose.reload.test.gradle.HotReloadTest
import org.jetbrains.compose.reload.test.gradle.HotReloadTestFixture
import org.jetbrains.compose.reload.test.gradle.ProjectMode
import org.jetbrains.compose.reload.test.gradle.TestedProjectMode
import org.jetbrains.compose.reload.test.gradle.checkScreenshot
import org.jetbrains.compose.reload.test.gradle.getDefaultMainKtSourceFile
import org.jetbrains.compose.reload.test.gradle.initialSourceCode
import org.jetbrains.compose.reload.test.gradle.launchApplicationAndWait
import org.jetbrains.compose.reload.test.gradle.replaceSourceCodeAndReload
import org.jetbrains.compose.reload.utils.GradleIntegrationTest
import org.jetbrains.compose.reload.utils.QuickTest
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.fail

class UpToDateTest {
    @HotReloadTest
    @GradleIntegrationTest
    @QuickTest
    @TestedProjectMode(ProjectMode.Kmp)
    @BuildGradleKts("")
    @BuildGradleKts("lib")
    fun `test - change hot - change cold`(fixture: HotReloadTestFixture) = fixture.runTest {
        projectDir.buildGradleKts.appendText(
            """
            kotlin {
                sourceSets.commonMain.dependencies {
                    implementation(project(":lib"))
                }
            }
        """.trimIndent()
        )

        val libKts = projectDir.subproject("lib").resolve(fixture.getDefaultMainKtSourceFile())
            .resolveSibling("lib.kt")
            .createParentDirectories()

        libKts.writeText(
            """
            fun lib() = "Before"
        """.trimIndent()
        )

        initialSourceCode(
            """
            import androidx.compose.foundation.layout.*
                import org.jetbrains.compose.reload.test.*
                
                fun main() {
                    screenshotTestApplication {
                        TestText(lib())
                    }
                }
        """.trimIndent()
        )
        checkScreenshot("0-before")

        /* Change while hot-reload is active */
        replaceSourceCodeAndReload(libKts.pathString, "Before", "Hot: 1")
        checkScreenshot("1-change1")

        /* Shutdown application and perform an 'offline/cold' change */
        fixture.sendMessage(ShutdownRequest("Explicitly requested by the test")) {
            skipToMessage<ClientDisconnected> { message -> message.clientRole == OrchestrationClientRole.Application }
        }

        /* Perform the 'offline'/'cold' change */
        runTransaction {
            replaceSourceCode(libKts.pathString, "Hot: 1", "Cold: 2")
        }

        /* Launch the application again and assert that the change is visible */
        val expectNoReloadRequest = launchTestDaemon {
            orchestration.asFlow().filterIsInstance<OrchestrationMessage.ReloadClassesRequest>().collect { request ->
                fail("Expected no reload request, got: $request")
            }
        }

        launchApplicationAndWait()
        val runClasses = projectDir.resolve("build/run/jvmMain/classpath/classes")
        val buildClasses = projectDir.resolve("build/classes/kotlin/jvm/main")

        assertEquals(DirectoryContent(runClasses), DirectoryContent(buildClasses))

        checkScreenshot("2-change2")
        val hotClasses = projectDir.resolve("build/run/jvmMain/classpath/hot")
        if (!hotClasses.isDirectory()) fail("Expected hot classes directory: $hotClasses")
        if (hotClasses.listDirectoryEntries().isNotEmpty())
            fail("Expected no hot classes: ${hotClasses.listDirectoryEntries()}")

        /* Check if reloading is alive and healthy, changing sources in lib and app */
        expectNoReloadRequest.cancel()

        replaceSourceCodeAndReload(libKts.pathString, "Cold: 2", "Hot: 3")
        checkScreenshot("3-change3")

        replaceSourceCodeAndReload("lib()", "\"Hot: 4\"")
        checkScreenshot("4-change4")
    }

    @QuickTest
    @HotReloadTest
    @BuildGradleKts("")
    @BuildGradleKts("lib")
    fun `test - flip - flop`(fixture: HotReloadTestFixture) = fixture.runTest {
        projectDir.buildGradleKts.appendText(
            """
            kotlin {
                sourceSets.commonMain.dependencies {
                    implementation(project(":lib"))
                }
            }
            """.trimIndent()
        )

        val libKts = projectDir.subproject("lib").resolve(fixture.getDefaultMainKtSourceFile())
            .resolveSibling("lib.kt")
            .createParentDirectories()

        libKts.writeText(
            """
            fun lib() = "Before"
        """.trimIndent()
        )

        fixture initialSourceCode """
            import androidx.compose.foundation.layout.*
            import androidx.compose.ui.unit.sp
            import androidx.compose.ui.window.*
            import org.jetbrains.compose.reload.test.*
            
            fun main() {
                screenshotTestApplication {
                    TestText(lib())
                }
            }
            """.trimIndent()
        fixture.checkScreenshot("0-before")

        fixture.replaceSourceCodeAndReload(libKts.pathString, "Before", "After")
        fixture.checkScreenshot("1-after")

        fixture.replaceSourceCodeAndReload(libKts.pathString, "After", "Before")
        fixture.checkScreenshot("0-before")

        fixture.replaceSourceCodeAndReload(libKts.pathString, "Before", "After")
        fixture.checkScreenshot("1-after")

        fixture.replaceSourceCodeAndReload("lib()", "\"Before\"")
        fixture.checkScreenshot("0-before")

        fixture.replaceSourceCodeAndReload("Before", "After")
        fixture.checkScreenshot("1-after")

        fixture.replaceSourceCodeAndReload("After", "Goodbye")
        fixture.checkScreenshot("2-goodbye")
    }
}

private class DirectoryContent(private val path: Path) {
    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String {
        return """
            Content of '$path':
               - {{path}} | ({{hash}})
        """.trimIndent().asTemplateOrThrow().renderOrThrow {
            toMap().forEach { (path, hash) ->
                "path"(path.pathString)
                "hash"(hash.toHexString())
            }
        }
    }

    override fun hashCode(): Int {
        return toMap().hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DirectoryContent) return false
        return toMap() == other.toMap()
    }

    private fun toMap(): Map<Path, Int> {
        return path.walk().filter { it.isRegularFile() }
            .associate { file -> file.relativeTo(path) to (file.readBytes().contentHashCode()) }
            .toSortedMap()
    }
}
