/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package builds.conventions

import jetbrains.buildServer.configs.kotlin.BuildType
import vcs.Github
import vcs.GithubTeamcityBranch

sealed interface VcsRootConvention {
    interface TeamcityBranch : VcsRootConvention
}

fun BuildType.vcsConventions() {
    val root = when (this) {
        is VcsRootConvention -> when (this) {
            is VcsRootConvention.TeamcityBranch -> GithubTeamcityBranch
        }
        else -> Github
    }

    vcs {
        root(root)
    }
}
