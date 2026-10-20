/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package builds.conventions

import builds.utils.Host
import jetbrains.buildServer.configs.kotlin.BuildType

sealed interface HostRequirement {
    interface Linux : HostRequirement
    interface MacOS : HostRequirement
    interface Windows : HostRequirement

    interface Dynamic : HostRequirement {
        val requiredHost: Host
    }
}

val HostRequirement.requiredHost: Host
    get() = when (this) {
        is HostRequirement.Dynamic -> requiredHost
        is HostRequirement.Linux -> Host.Linux
        is HostRequirement.MacOS -> Host.MacOS
        is HostRequirement.Windows -> Host.Windows
    }

val BuildType.requiredHost: Host
    get() = when (this) {
        is HostRequirement -> (this as HostRequirement).requiredHost
        else -> Host.Linux
    }

fun BuildType.hostRequirementConventions() {
    val host = requiredHost

    when (host) {
        Host.Linux -> requirements {
            matches("teamcity.agent.jvm.os.family", "Linux")
            matches("teamcity.agent.cpuArchitecture", "x86_64")
        }
        Host.Windows -> {
            requirements {
                matches("teamcity.agent.jvm.os.family", "Windows")
            }
        }
        Host.MacOS -> requirements {
            contains("teamcity.agent.jvm.os.name", "Mac")
            matches("teamcity.agent.jvm.os.arch", "aarch64")
        }
    }
}
