/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package builds.conventions

import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.buildSteps.GradleBuildStep

interface PublishLocallyConvention

fun BuildType.publishLocallyConventions() {
    if (this !is PublishLocallyConvention) return

    params {
        param("bootstrap", "false")
    }

    steps {
        items.add(0, GradleBuildStep {
            name = "Publish Locally"
            tasks = "publishLocally"
        })

        items.add(0, GradleBuildStep {
            conditions {
                matches("bootstrap", "true")
            }

            name = "Publish Bootstrap"
            tasks = "publishBootstrap"
        })
    }
}
