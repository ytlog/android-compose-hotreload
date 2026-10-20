/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package builds.conventions

import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.buildFeatures.commitStatusPublisher
import vcs.Github

interface CommitStatusPublisher

fun BuildType.commitPublisherConventions() {
    if (this !is CommitStatusPublisher) return

    features {
        commitStatusPublisher {
            vcsRootExtId = "${Github.id}"
            publisher = github {
                githubUrl = "https://api.github.com"
                authType = personalToken {
                    token = "credentialsJSON:63ad183a-fe82-4c2f-b80d-f92b2d7b69ec"
                }
            }
        }
    }
}
