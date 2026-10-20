/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package builds

import builds.conventions.BuildCacheConvention
import builds.conventions.CommitStatusPublisher
import builds.conventions.HardwareCapacity
import builds.conventions.HostRequirement
import builds.conventions.requiredHost
import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle

class WindowsTest() : BuildType({
    name = "Tests: $requiredHost"
    id("Tests_$requiredHost")

    params {
        param("bootstrap", "false")
    }

    artifactRules = """
        **/*-actual*
        **/build/reports/** => reports.zip
    """.trimIndent()

    steps {
        gradle {
            name = "Bootstrap"
            tasks = "publishBootstrap -i"
            conditions {
                matches("bootstrap", "true")
            }
        }

        gradle {
            name = "Test"
            tasks = "check -i --continue -x apiCheck " +
                "-x reloadFunctionalTest -x reloadFunctionalTestWarmup " +
                "-Pchr.tests.parallelism=2"
        }
    }
}), CommitStatusPublisher,
    HostRequirement.Windows,
    HardwareCapacity.Large,
    BuildCacheConvention.Consumer
