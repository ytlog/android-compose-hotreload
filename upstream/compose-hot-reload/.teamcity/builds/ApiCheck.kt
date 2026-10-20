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

object ApiCheck : BuildType({
    name = "Check: api"
    description = "Checks API compatibility"

    steps {
        gradle {
            name = "Check API"
            tasks = "apiCheck"
        }
    }

}), CommitStatusPublisher,
    PublishLocallyConvention,
    HardwareCapacity.Medium,
    BuildCacheConvention.Consumer
