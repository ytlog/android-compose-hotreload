/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.core

import org.jetbrains.compose.reload.DelicateHotReloadApi

/**
 * Every event will be received by all [collect]'ing coroutines.
 */
@DelicateHotReloadApi
public interface Broadcast<T> {
    public suspend fun collect(action: suspend (T) -> Unit)
}

/**
 * Registers the [acton] to be called on every event.
 * Note: The registration is performed on the [reloadMainThread] and it is ensured that the
 * listener is registered immediately. This ensures that no messages will be lost.
 */
@DelicateHotReloadApi
public fun <T> Broadcast<T>.invokeOnValue(acton: (T) -> Unit): Disposable = reloadMainThread.invokeImmediateBlocking {
    val task = launchTask("Broadcast.invokeOnValue", context = reloadMainDispatcherImmediate) {
        collect { value -> acton(value) }
    }

    Disposable {
        task.stop()
    }
}

@DelicateHotReloadApi
public inline fun <reified T> Broadcast<*>.withType(): Broadcast<T> {
    return object : Broadcast<T> {
        override suspend fun collect(action: suspend (T) -> Unit) {
            this@withType.collect {
                if (it is T) action(it)
            }
        }
    }
}
