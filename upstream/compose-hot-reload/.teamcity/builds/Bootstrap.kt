/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package builds

import builds.conventions.HardwareCapacity
import builds.conventions.PublishDevPrivilege
import builds.conventions.PushPrivilege
import builds.conventions.setupGit
import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.FailureAction
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.vcs

object BootstrapDeploy : BuildType({
    name = "Deploy: Bootstrap -> Staging"
    type = Type.DEPLOYMENT

    vcs {
        cleanCheckout = true

        branchFilter = """
            +:bootstrap
            +:bootstrap/*
        """.trimIndent()
    }

    params {
        param("reverse.dep.*.bootstrap", "true")
    }

    triggers {
        vcs {
            branchFilter = "+:bootstrap"
        }
    }

    dependencies {
        snapshot(AllTests) {
            this.onDependencyFailure = FailureAction.FAIL_TO_START
            this.onDependencyCancel = FailureAction.CANCEL
        }
    }

    steps {
        setupGit()

        gradle {
            workingDir = "repository-tools"
            name = "Bump Dev Version"
            tasks = "bumpDevVersion"
        }

        gradle {
            name = "publish Bootstrap"
            tasks = "publishBootstrap"
        }

        gradle {
            workingDir = "repository-tools"
            name = "Bump Bootstrap Version"
            tasks = "bumpBootstrapVersion"
        }

        gradle {
            name = "Publish to Firework Repository"
            tasks = "publishAllPublicationsToFireworkRepository --no-configuration-cache"
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
}), PushPrivilege, HardwareCapacity.Large, PublishDevPrivilege
