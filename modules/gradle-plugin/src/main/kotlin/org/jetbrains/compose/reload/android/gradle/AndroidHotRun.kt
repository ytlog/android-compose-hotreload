package org.jetbrains.compose.reload.android.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.compose.reload.android.gradle.upstream.automaticReloadEnabled
import org.jetbrains.compose.reload.android.gradle.upstream.configureUpstreamArguments
import java.io.File
import java.util.Properties

/**
 * upstream 'ComposeHotRun' for Android: installs the variant, then runs the host process
 * (in place of the application's JVM). The task runs as long as the application runs.
 */
@UntrackedTask(because = "This task should always run")
abstract class AndroidHotRun : JavaExec() {
    @get:Internal
    abstract val adb: RegularFileProperty

    @get:Internal
    abstract val applicationId: Property<String>

    @get:Internal
    abstract val minSdk: Property<Int>

    @get:Classpath
    abstract val bootClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val applicationClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val hostConfigFile: RegularFileProperty

    /** Same as upstream 'AbstractComposeHotRun.isAutoReloadEnabled' */
    @get:Internal
    @get:JvmName("getIsAutoReloadEnabled")
    val isAutoReloadEnabled: Property<Boolean> = project.objects.property(Boolean::class.java)

    @Option(option = "autoReload", description = "Enables automatic recompilation/reload once the source files change")
    @Suppress("unused")
    fun autoReload(enabled: Boolean) {
        isAutoReloadEnabled.set(enabled)
    }

    @Option(option = "auto", description = "Enables automatic recompilation/reload once the source files change")
    @Suppress("unused")
    fun auto(enabled: Boolean) {
        isAutoReloadEnabled.set(enabled)
    }

    @TaskAction
    override fun exec() {
        val configFile = hostConfigFile.get().asFile
        configFile.parentFile.mkdirs()
        val properties = Properties()
        properties["adb"] = adb.get().asFile.absolutePath
        properties["serial"] = System.getenv("ANDROID_SERIAL").orEmpty()
        properties["applicationId"] = applicationId.get()
        properties["minSdk"] = minSdk.get().toString()
        properties["bootClasspath"] = bootClasspath.files.joinToString(File.pathSeparator)
        properties["applicationClasspath"] = applicationClasspath.files.joinToString(File.pathSeparator)
        configFile.outputStream().use { properties.store(it, "Compose Hot Reload: Android host") }
        systemProperty(HOST_CONFIG_PROPERTY, configFile.absolutePath)
        super.exec()
    }

    internal companion object {
        /** see 'HostConfig' */
        const val HOST_CONFIG_PROPERTY = "compose.reload.android.config"
        const val HOST_MAIN_CLASS = "org.jetbrains.compose.reload.android.host.AndroidHotReloadHost"
    }
}

internal fun Project.registerHotRunTask(
    variant: ApplicationVariant,
    components: ApplicationAndroidComponentsExtension,
    name: String,
    pidFile: Provider<RegularFile>,
    reloadTask: TaskProvider<*>,
    snapshotTask: TaskProvider<*>,
    hotFiles: FileCollection,
    coldFiles: FileCollection,
) {
    val javaToolchains = extensions.getByType(JavaToolchainService::class.java)

    tasks.register("hotRun$name", AndroidHotRun::class.java) { task ->
        task.group = GROUP_RUN
        task.description = "Runs the '${variant.name}' variant with Compose Hot Reload enabled | --auto"
        task.dependsOn("install$name")

        /* upstream: Wire up the dependency to the 'snapshot' task */
        task.dependsOn(snapshotTask)

        task.classpath = hostConfiguration
        task.mainClass.set(AndroidHotRun.HOST_MAIN_CLASS)

        task.isAutoReloadEnabled.convention(
            automaticReloadEnabled
        )

        task.configureUpstreamArguments(pidFile, reloadTask.name)

        /* The application runs on ART: the host does not need the JetBrains Runtime */
        task.javaLauncher.set(javaToolchains.launcherFor { it.languageVersion.set(JavaLanguageVersion.of(21)) })

        task.adb.set(components.sdkComponents.adb)
        task.bootClasspath.from(components.sdkComponents.bootClasspath)
        task.applicationId.set(variant.applicationId)
        task.minSdk.set(variant.minSdk.apiLevel)
        task.applicationClasspath.from(hotFiles, coldFiles)
        task.hostConfigFile.set(pidFile.map { it.asFile.resolveSibling("host.properties") }.let { file ->
            layout.file(file)
        })
    }
}

/** The host process classpath (resolved like the upstream devtools classpath) */
private val Project.hostConfiguration: Configuration
    get() = configurations.findByName(HOST_CONFIGURATION) ?: configurations.create(HOST_CONFIGURATION) { configuration ->
        configuration.isCanBeConsumed = false
        configuration.isCanBeResolved = true
        configuration.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        configuration.attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        configuration.attributes.attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM)
        )
        configuration.dependencies.add(
            dependencies.create("${BuildConfig.GROUP}:hot-reload-android-host:${BuildConfig.VERSION}")
        )
    }

private const val HOST_CONFIGURATION = "composeHotReloadAndroidHost"
internal const val GROUP_RUN = "Compose Hot Reload"
