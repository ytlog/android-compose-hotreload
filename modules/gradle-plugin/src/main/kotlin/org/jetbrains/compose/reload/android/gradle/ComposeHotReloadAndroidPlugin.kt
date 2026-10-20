package org.jetbrains.compose.reload.android.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.jetbrains.compose.reload.android.gradle.upstream.registerUpstreamReloadTasks
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Compose Hot Reload for Android applications.
 *
 * Mirrors the upstream plugin for each debuggable application variant (e.g. 'debug'):
 * - 'hotSnapshotDebug' / 'hotReloadDebug': the upstream 'ComposeHotSnapshotTask' / 'ComposeHotReloadTask'
 * - 'reload': the upstream lifecycle task, reloading all running applications
 * - 'hotRunDebug': installs and starts the application with Compose Hot Reload (upstream 'hotRun')
 */
class ComposeHotReloadAndroidPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            project.configureAndroidHotReload()
        }

        /* upstream 'configureComposeCompilerArgs' */
        project.pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
            project.tasks.withType(KotlinJvmCompile::class.java).configureEach { task ->
                task.compilerOptions.freeCompilerArgs.addAll(
                    "-P", "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true"
                )
            }
        }
    }
}

private const val GROUP = "Compose Hot Reload"

private fun Project.configureAndroidHotReload() {
    val android = extensions.getByType(ApplicationExtension::class.java)
    val components = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

    /* The runtime is only added to debuggable build types (ART only allows redefinition in debuggable apps) */
    components.finalizeDsl { extension ->
        extension.buildTypes.filter { it.isDebuggable }.forEach { buildType ->
            dependencies.add("${buildType.name}Implementation", "${BuildConfig.GROUP}:hot-reload-android-runtime:${BuildConfig.VERSION}")
        }
    }

    val reloadLifecycleTask = tasks.register("reload") { task ->
        task.group = GROUP
        task.description = "Hot Reloads code for all running applications"
    }

    components.onVariants(components.selector().all()) { variant ->
        val buildType = variant.buildType ?: return@onVariants
        if (android.buildTypes.getByName(buildType).isDebuggable != true) return@onVariants
        configureVariant(variant, components, reloadLifecycleTask)
    }
}

private fun Project.configureVariant(
    variant: ApplicationVariant,
    components: ApplicationAndroidComponentsExtension,
    reloadLifecycleTask: TaskProvider<*>,
) {
    val name = variant.name.replaceFirstChar { it.uppercaseChar() }
    val runDirectory = layout.buildDirectory.dir("run/${variant.name}")
    val pidFile = runDirectory.map { it.file("${variant.name}.pid") }

    /* Resource invalidation needs the entry point even when the devtools window is disabled. */
    variant.instrumentation.transformClassesWith(
        EntryPointInstrumentation::class.java, InstrumentationScope.ALL
    ) { }
    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)

    val hotFiles = hotClasspath(variant)
    val coldFiles = coldClasspath(variant)

    val resourceSnapshot = tasks.register("hotResources$name", AndroidResourceSnapshot::class.java) { task ->
        task.apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        task.artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        task.aapt2.set(components.sdkComponents.aapt2.flatMap { it.executable })
        task.outputDirectory.set(runDirectory.map { it.dir("resources") })
    }
    val resources = resourceSnapshot.flatMap { it.outputDirectory }
    val upstreamTasks = registerUpstreamReloadTasks(
        name, variant.name, runDirectory, pidFile, files(hotFiles, resources), reloadLifecycleTask
    )

    registerHotRunTask(variant, components, name, pidFile, upstreamTasks.reload, upstreamTasks.snapshot,
        hotFiles, coldFiles, resources.map { it.file(RESOURCE_BUNDLE) })
}

/**
 * upstream 'hotRuntimeFiles': the outputs of this project and of project dependencies.
 * These classes are compared by the snapshot task and sent with 'ReloadClassesRequest'.
 */
private fun Project.hotClasspath(variant: ApplicationVariant): FileCollection {
    val name = variant.name.replaceFirstChar { it.uppercaseChar() }
    val projectClasses = files(
        provider {
            tasks.withType(KotlinJvmCompile::class.java).matching { it.name == "compile${name}Kotlin" }
                .map { it.destinationDirectory }
        },
        provider {
            tasks.withType(JavaCompile::class.java).matching { it.name == "compile${name}JavaWithJavac" }
                .map { it.destinationDirectory }
        },
    )
    projectClasses.builtBy("compile${name}Kotlin", "compile${name}JavaWithJavac")

    val projectDependencies = variant.runtimeConfiguration.incoming.artifactView { view ->
        view.componentFilter { it is ProjectComponentIdentifier }
        view.attributes.attribute(ARTIFACT_TYPE, "android-classes-jar")
    }.files

    return files(projectClasses, projectDependencies)
}

/** Library classes: indexed by the host for the upstream analysis and used as D8 classpath */
private fun Project.coldClasspath(variant: ApplicationVariant): FileCollection {
    return variant.runtimeConfiguration.incoming.artifactView { view ->
        view.componentFilter { it !is ProjectComponentIdentifier }
        view.attributes.attribute(ARTIFACT_TYPE, "android-classes-jar")
    }.files
}

private val ARTIFACT_TYPE = Attribute.of("artifactType", String::class.java)
