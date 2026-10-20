/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package builds

import builds.conventions.BuildCacheConvention
import builds.conventions.CommitStatusPublisher
import builds.conventions.HardwareCapacity
import builds.conventions.HostRequirement
import builds.conventions.PublishLocallyConvention
import builds.utils.Host
import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle

class Test(
    override val requiredHost: Host
) : BuildType({
    name = "Tests: $requiredHost"
    id("Tests_$requiredHost")

    artifactRules = """
        **/*-actual*
        **/build/reports/** => reports.zip
    """.trimIndent()

    steps {
        gradle {
            name = "Test"
            tasks =
                "check -i --continue -x apiCheck -x publishLocally -x reloadFunctionalTest -x reloadFunctionalTestWarmup"

            /* Any host other than linux is considered to only run 'host integration tests' */
            if (requiredHost != Host.Linux) {
                tasks += " -Phost-integration-tests=true"
            }
        }
    }
}), PublishLocallyConvention,
    CommitStatusPublisher,
    HostRequirement.Dynamic,
    HardwareCapacity.Medium,
    BuildCacheConvention.Consumer
