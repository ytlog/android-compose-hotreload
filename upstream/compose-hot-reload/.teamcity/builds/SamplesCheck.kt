/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package builds

import builds.conventions.BuildCacheConvention
import builds.conventions.CommitStatusPublisher
import builds.conventions.HardwareCapacity
import builds.conventions.PublishLocallyConvention
import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle

object SamplesCheck : BuildType({
    name = "Check: sample projects"
    description = "Checks samples"

    steps {
        gradle {
            name = "Check: counter"
            tasks = "check"
            workingDir = "samples/counter"
        }

        gradle {
            name = "Check: bytecode-analyzer"
            tasks = "check"
            workingDir = "samples/bytecode-analyzer"
        }
    }
}), CommitStatusPublisher,
    PublishLocallyConvention,
    HardwareCapacity.Medium,
    BuildCacheConvention.Consumer
