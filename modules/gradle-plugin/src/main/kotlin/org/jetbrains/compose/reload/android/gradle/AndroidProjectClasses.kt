package org.jetbrains.compose.reload.android.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.work.DisableCachingByDefault

/** Dependency anchor for AGP's final project classes, including generators and instrumentation. */
@DisableCachingByDefault(because = "Exposes AGP artifacts without producing or copying files")
abstract class AndroidProjectClasses : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Classpath
    abstract val directories: ListProperty<Directory>
}
