/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

@file:Suppress("DuplicatedCode")

package tests

import androidx.compose.material.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.jetbrains.compose.reload.test.HotReloadUnitTest
import org.jetbrains.compose.reload.test.compileAndReload

object InvalidateRemember {
    @Suppress("MayBeConstant")
    val initialValue = 0
}

@OptIn(ExperimentalTestApi::class)
@HotReloadUnitTest
fun `test - all remember overloads are invalidated - change in overload 0`() = runComposeUiTest {
    setContent {
        DevelopmentEntryPoint {
            val overload0 = remember { InvalidateRemember.initialValue }
            val overload1 = remember(1) { overload0 + 1 }
            val overload2 = remember(1, 2) { overload0 + 2 }
            val overload3 = remember(1, 2, 3) { overload0 + 3 }
            val overload4 = remember(1, 2, 3, 4) { overload0 + 4 }

            Text(overload0.toString(), Modifier.testTag("overload0"))
            Text(overload1.toString(), Modifier.testTag("overload1"))
            Text(overload2.toString(), Modifier.testTag("overload2"))
            Text(overload3.toString(), Modifier.testTag("overload3"))
            Text(overload4.toString(), Modifier.testTag("overload4"))
        }
    }

    onNodeWithTag("overload0").assertTextEquals("0")
    onNodeWithTag("overload1").assertTextEquals("1")
    onNodeWithTag("overload2").assertTextEquals("2")
    onNodeWithTag("overload3").assertTextEquals("3")
    onNodeWithTag("overload4").assertTextEquals("4")

    compileAndReload("""
        package tests
        
        object InvalidateRemember {
            val initialValue = 10
        }
    """.trimIndent())

    onNodeWithTag("overload0").assertTextEquals("10")
    onNodeWithTag("overload1").assertTextEquals("11")
    onNodeWithTag("overload2").assertTextEquals("12")
    onNodeWithTag("overload3").assertTextEquals("13")
    onNodeWithTag("overload4").assertTextEquals("14")
}

@OptIn(ExperimentalTestApi::class)
@HotReloadUnitTest
fun `test - all remember overloads are invalidated - change in overload 1`() = runComposeUiTest {
    setContent {
        DevelopmentEntryPoint {
            val overload1 = remember(1) { InvalidateRemember.initialValue + 1 }
            val overload2 = remember(1, 2) { overload1 + 1 }
            val overload3 = remember(1, 2, 3) { overload1 + 2 }
            val overload4 = remember(1, 2, 3, 4) { overload1 + 3 }

            Text(overload1.toString(), Modifier.testTag("overload1"))
            Text(overload2.toString(), Modifier.testTag("overload2"))
            Text(overload3.toString(), Modifier.testTag("overload3"))
            Text(overload4.toString(), Modifier.testTag("overload4"))
        }
    }

    onNodeWithTag("overload1").assertTextEquals("1")
    onNodeWithTag("overload2").assertTextEquals("2")
    onNodeWithTag("overload3").assertTextEquals("3")
    onNodeWithTag("overload4").assertTextEquals("4")

    compileAndReload("""
        package tests
        
        object InvalidateRemember {
            val initialValue = 10
        }
    """.trimIndent())

    onNodeWithTag("overload1").assertTextEquals("11")
    onNodeWithTag("overload2").assertTextEquals("12")
    onNodeWithTag("overload3").assertTextEquals("13")
    onNodeWithTag("overload4").assertTextEquals("14")
}

@OptIn(ExperimentalTestApi::class)
@HotReloadUnitTest
fun `test - all remember overloads are invalidated - change in overload 2`() = runComposeUiTest {
    setContent {
        DevelopmentEntryPoint {
            val overload2 = remember(1, 2) { InvalidateRemember.initialValue + 2 }
            val overload3 = remember(1, 2, 3) { overload2 + 1 }
            val overload4 = remember(1, 2, 3, 4) { overload2 + 2 }

            Text(overload2.toString(), Modifier.testTag("overload2"))
            Text(overload3.toString(), Modifier.testTag("overload3"))
            Text(overload4.toString(), Modifier.testTag("overload4"))
        }
    }

    onNodeWithTag("overload2").assertTextEquals("2")
    onNodeWithTag("overload3").assertTextEquals("3")
    onNodeWithTag("overload4").assertTextEquals("4")

    compileAndReload("""
        package tests
        
        object InvalidateRemember {
            val initialValue = 10
        }
    """.trimIndent())

    onNodeWithTag("overload2").assertTextEquals("12")
    onNodeWithTag("overload3").assertTextEquals("13")
    onNodeWithTag("overload4").assertTextEquals("14")
}


@OptIn(ExperimentalTestApi::class)
@HotReloadUnitTest
fun `test - all remember overloads are invalidated - change in overload 3`() = runComposeUiTest {
    setContent {
        DevelopmentEntryPoint {
            val overload3 = remember(1, 2, 3) { InvalidateRemember.initialValue + 3 }
            val overload4 = remember(1, 2, 3, 4) { overload3 + 1 }

            Text(overload3.toString(), Modifier.testTag("overload3"))
            Text(overload4.toString(), Modifier.testTag("overload4"))
        }
    }

    onNodeWithTag("overload3").assertTextEquals("3")
    onNodeWithTag("overload4").assertTextEquals("4")

    compileAndReload("""
        package tests
        
        object InvalidateRemember {
            val initialValue = 10
        }
    """.trimIndent())

    onNodeWithTag("overload3").assertTextEquals("13")
    onNodeWithTag("overload4").assertTextEquals("14")
}


@OptIn(ExperimentalTestApi::class)
@HotReloadUnitTest
fun `test - all remember overloads are invalidated - change in overload 4`() = runComposeUiTest {
    setContent {
        DevelopmentEntryPoint {
            val overload4 = remember(1, 2, 3, 4) { InvalidateRemember.initialValue + 4 }
            val overload0 = remember { overload4 + 1 }

            Text(overload4.toString(), Modifier.testTag("overload4"))
            Text(overload0.toString(), Modifier.testTag("overload0"))
        }
    }

    onNodeWithTag("overload4").assertTextEquals("4")
    onNodeWithTag("overload0").assertTextEquals("5")

    compileAndReload("""
        package tests
        
        object InvalidateRemember {
            val initialValue = 10
        }
    """.trimIndent())

    onNodeWithTag("overload4").assertTextEquals("14")
    onNodeWithTag("overload0").assertTextEquals("15")
}
