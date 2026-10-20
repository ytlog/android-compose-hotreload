package org.jetbrains.compose.reload.android.host.dex

import com.android.tools.r8.ArchiveClassFileProvider
import com.android.tools.r8.ByteDataView
import com.android.tools.r8.ClassFileResourceProvider
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DexFilePerClassFileConsumer
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.DirectoryClassFileProvider
import com.android.tools.r8.ProgramResource
import com.android.tools.r8.origin.Origin
import org.jetbrains.compose.reload.analysis.ClassId
import java.io.File

/**
 * Converts redefined JVM classes to DEX: one DEX per class (also for synthetic classes, such as desugared lambdas),
 * since ART redefines classes from single-class DEX files.
 */
internal class Dexer(
    private val minApi: Int,
    bootClasspath: List<File>,
    private val applicationClasspath: List<File>,
) {
    private val libraryProviders = bootClasspath.mapNotNull(::provider)

    /** @return binary class name (e.g. 'com.example.Foo$$ExternalSyntheticLambda0') to DEX bytes */
    fun dex(classes: Map<ClassId, ByteArray>): Map<String, ByteArray> {
        if (classes.isEmpty()) return emptyMap()
        val programDescriptors = classes.keys.mapTo(HashSet()) { "L${it.value};" }
        val result = linkedMapOf<String, ByteArray>()

        val consumer = object : DexFilePerClassFileConsumer {
            override fun accept(
                primaryClassDescriptor: String, data: ByteDataView,
                descriptors: MutableSet<String>?, handler: DiagnosticsHandler?
            ) {
                synchronized(result) {
                    result[primaryClassDescriptor.removePrefix("L").removeSuffix(";").replace('/', '.')] = data.copyByteData()
                }
            }

            /* Keep synthetic classes in their own DEX (one class per DEX) */
            override fun combineSyntheticClassesWithPrimaryClass(): Boolean = false

            override fun finished(handler: DiagnosticsHandler?) = Unit
        }

        /* Opened per invocation: project jars are rebuilt between reloads */
        val classpathProviders = applicationClasspath.mapNotNull(::provider)
        val builder = D8Command.builder()
            .setMode(CompilationMode.DEBUG)
            .setMinApiLevel(minApi)
            .setProgramConsumer(consumer)
            .apply { libraryProviders.forEach { addLibraryResourceProvider(it) } }
            /* The program classes shadow their previous version on the application classpath */
            .addClasspathResourceProvider(ExcludingProvider(classpathProviders, programDescriptors))

        classes.forEach { (classId, bytes) ->
            builder.addClassProgramData(bytes, Origin.unknown())
        }

        try {
            D8.run(builder.build())
        } finally {
            classpathProviders.forEach { (it as? AutoCloseable)?.close() }
        }
        return result
    }

    private class ExcludingProvider(
        private val delegates: List<ClassFileResourceProvider>,
        private val excluded: Set<String>,
    ) : ClassFileResourceProvider {
        private val descriptors by lazy {
            delegates.flatMapTo(HashSet()) { it.classDescriptors } - excluded
        }

        override fun getClassDescriptors(): Set<String> = descriptors

        override fun getProgramResource(descriptor: String): ProgramResource? {
            if (descriptor in excluded) return null
            return delegates.firstNotNullOfOrNull { it.getProgramResource(descriptor) }
        }
    }

    private companion object {
        fun provider(file: File): ClassFileResourceProvider? = when {
            file.isDirectory -> DirectoryClassFileProvider.fromDirectory(file.toPath())
            file.isFile && (file.extension == "jar" || file.extension == "zip") -> ArchiveClassFileProvider(file.toPath())
            else -> null
        }
    }
}
