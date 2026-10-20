/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.utils

import org.jetbrains.compose.reload.test.gradle.SettingsGradleKtsRepositoriesExtension
import org.jetbrains.compose.reload.test.gradle.hotReloadTestInvocationContext
import org.jetbrains.compose.reload.test.gradle.testedKotlinVersion
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.junit.jupiter.api.extension.ExtensionContext

class BootstrapMavenRepositoryExtension : SettingsGradleKtsRepositoriesExtension {
    override fun repositories(context: ExtensionContext): String? {
        if (context.hotReloadTestInvocationContext == null) return null

        if (context.testedKotlinVersion.version.maturity > KotlinToolingVersion.Maturity.ALPHA) {
            return null
        }

        return """
            maven("https://packages.jetbrains.team/maven/p/kt/bootstrap/") {
                mavenContent {
                    includeGroupAndSubgroups("org.jetbrains.kotlin")
                }
            }
        """.trimIndent()
    }
}
