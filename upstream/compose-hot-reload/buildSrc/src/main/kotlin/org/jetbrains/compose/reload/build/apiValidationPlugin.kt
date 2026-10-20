/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

@file:OptIn(ExperimentalBCVApi::class)

package org.jetbrains.compose.reload.build

import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.BinaryCompatibilityValidatorPlugin
import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

open class ApiValidationPlugin : Plugin<Project> {
    override fun apply(target: Project) = withProject(target) {
        target.plugins.apply(BinaryCompatibilityValidatorPlugin::class.java)
        extensions.configure<ApiValidationExtension> {
            klib { enabled = true }
            nonPublicMarkers += "org.jetbrains.compose.reload.gradle.InternalHotReloadGradleApi"
            nonPublicMarkers += "org.jetbrains.compose.reload.InternalHotReloadApi"
            nonPublicMarkers += "org.jetbrains.compose.reload.test.core.InternalHotReloadTestApi"
        }
    }
}
