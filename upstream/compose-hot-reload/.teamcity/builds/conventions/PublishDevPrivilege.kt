/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package builds.conventions

import jetbrains.buildServer.configs.kotlin.BuildType
import jetbrains.buildServer.configs.kotlin.ParameterDisplay

interface PublishDevPrivilege

fun BuildType.publishDevPrivilegeConventions() {
    if (this !is PublishDevPrivilege) return

    params {
        password(
            "env.ORG_GRADLE_PROJECT_repo.sellmair.user",
            "credentialsJSON:cd95c3bc-8dd9-46f0-b396-aecd26c0e4ca",
            display = ParameterDisplay.HIDDEN,
        )

        password(
            "env.ORG_GRADLE_PROJECT_repo.sellmair.password",
            "credentialsJSON:5eb3a3e5-22b9-4d9b-8e00-7546d6f4dc7b",
            display = ParameterDisplay.HIDDEN,
        )

        password(
            "env.ORG_GRADLE_PROJECT_fireworkUsername",
            "Sebastian.Sellmair",
            display = ParameterDisplay.HIDDEN,
        )

        password(
            "env.ORG_GRADLE_PROJECT_fireworkPassword",
            "credentialsJSON:0c2c7f64-7532-4e25-b950-7317f831eda4",
            display = ParameterDisplay.HIDDEN,
        )

        password(
            "env.ORG_GRADLE_PROJECT_signing.key",
            "credentialsJSON:a8763adb-f827-47c7-a463-344294cd4850",
            display = ParameterDisplay.HIDDEN,
        )

        password(
            "env.ORG_GRADLE_PROJECT_signing.key.password",
            "credentialsJSON:55dbddf8-050d-4139-8a8c-82ede4c58523",
            display = ParameterDisplay.HIDDEN,
        )
    }
}
