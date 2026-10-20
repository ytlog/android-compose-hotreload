/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.analysis

/**
 * The actual key emitted by the Compose Compiler associated with the group.
 * For example, a call like
 * ```kotlin
 * startRestartGroup(1902)
 *```
 *
 * will have the value '1902' recorded as [ComposeGroupKey].
 */
@JvmInline
value class ComposeGroupKey(val key: Int)

fun ComposeGroupKey(key: Int?): ComposeGroupKey? = key?.let(::ComposeGroupKey)

internal object SpecialComposeGroupKeys {
    /**
     * W/o 'OptimizeNonSkippingGroup' each call to remember is wrapped in its own group with this key.
     */
    val remember = ComposeGroupKey(1849434622)

    /**
     * Remember-Group-key of the overload with a single argument:
     * `remember(key) {}`
     */
    val remember1 = ComposeGroupKey(5004770)

    /**
     * Remember-Group-key of the overload with two arguments:
     * `remember(key1, key2) {}`
     */
    val remember2 = ComposeGroupKey(-1633490746)

    /**
     * Remember-Group-key of the overload with three arguments:
     * `remember(key1, key2, key3) {}`
     */
    val remember3 = ComposeGroupKey(-1746271574)

    /**
     * Remember-Group-key of the overload with vararg arguments:
     * `remember(key1, key2, key3, key4, ...) {}`
     */
    val remember4 = ComposeGroupKey(-1224400529)

    fun isRememberGroup(key: ComposeGroupKey): Boolean =
        key == remember ||
            key == remember1 ||
            key == remember2 ||
            key == remember3 ||
            key == remember4

}
