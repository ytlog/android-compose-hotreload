/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package builds.conventions

import jetbrains.buildServer.configs.kotlin.BuildType

sealed interface HardwareCapacity {
    interface Small : HardwareCapacity
    interface Medium : HardwareCapacity
    interface Large : HardwareCapacity
    interface XLarge : HardwareCapacity
}

fun BuildType.hardwareCapacity() {
    val capacity = when (this) {
        is HardwareCapacity.Small -> "Small"
        is HardwareCapacity.Medium -> "Medium"
        is HardwareCapacity.Large -> "Large"
        is HardwareCapacity.XLarge -> "XLarge"
        else -> "Medium"
    }

    requirements {
        matches("teamcity.agent.hardwareCapacity", capacity)
    }
}
