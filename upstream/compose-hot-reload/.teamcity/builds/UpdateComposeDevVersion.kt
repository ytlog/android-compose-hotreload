/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package builds

import builds.conventions.HardwareCapacity
import builds.conventions.PushPrivilege
import builds.conventions.setupGit
import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.ScheduleTrigger
import jetbrains.buildServer.configs.kotlin.triggers.schedule

object UpdateComposeDevVersion : BuildType({
    name = "Update Compose Dev Version"
    type = Type.DEPLOYMENT

    vcs {
        branchFilter = "+:staging"
    }

    triggers {
        schedule {
            branchFilter = "+:staging"
            schedulingPolicy = weekly {
                dayOfWeek = ScheduleTrigger.DAY.Sunday
                timezone = "Europe/Berlin"
                hour = 2
            }
        }
    }

    steps {
        setupGit()
        gradle {
            workingDir = "repository-tools"
            name = "Update Compose Dev Version"
            tasks = "updateComposeDevVersion"
        }
        script {
            name = "Push"
            scriptContent = """
                set -e
                git remote -v
                git log %build.vcs.number%
                git fetch --unshallow origin staging
                git pull origin staging --rebase
                git push origin HEAD:refs/heads/staging -v
            """.trimIndent()
        }
    }
}), PushPrivilege, HardwareCapacity.Medium
