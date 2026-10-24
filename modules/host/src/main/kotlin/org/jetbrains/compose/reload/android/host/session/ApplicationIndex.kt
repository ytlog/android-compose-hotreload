package org.jetbrains.compose.reload.android.host.session

import org.jetbrains.compose.reload.android.host.upstream.UpstreamAnalysis

import org.jetbrains.compose.reload.android.host.dex.LambdaClassState
import javassist.ClassPool
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.ClassInfo
import org.jetbrains.compose.reload.analysis.MutableApplicationInfo
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.info
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.time.measureTime

private val logger = createLogger()

/**
 * Host side replacement of the upstream agent's 'runtimeTracking.kt'.
 *
 * Upstream records a 'ClassInfo' for every class loaded by the JVM (via a 'ClassFileTransformer').
 * ART exposes no class file bytes, so the index is built from the class files the installed APK was dexed from:
 * - [currentApplicationInfo] mirrors upstream 'currentApplicationInfo'
 * - [pendingRedefinitions] mirrors upstream 'pendingRedefinitions'
 */
internal class ApplicationIndex(
    private val classpath: List<File>,
    private val bootClasspath: List<File>,
) {
    val currentApplicationInfo = MutableApplicationInfo()
    val pendingRedefinitions = MutableApplicationInfo()

    /**
    Bytecode of every class of the installed application (including ignored ones), as currently run by the device.
    A snapshot: build outputs change before they are reloaded.
     */
    private data class Definition(val original: ByteArray, val lambdas: LambdaClassState? = null)
    private val definitions = HashMap<ClassId, Definition>()

    init {
        val duration = measureTime { index(classpath) }
        logger.info("Indexed ${definitions.size} classes (${currentApplicationInfo.classIndex.size} tracked) in $duration")
    }

    /** Equivalent to upstream: the class can be found by the application ('findClassLoader(classId) != null') */
    fun isKnown(classId: ClassId): Boolean = classId in definitions

    fun bytecode(classId: ClassId): ByteArray? = definitions[classId]?.original

    fun lambdaState(classId: ClassId): LambdaClassState? = definitions[classId]?.lambdas

    /**
     * Upstream uses a pool backed by the application class loader ('getClassPool(loader)').
     * Created per reload: project classes change between reloads.
     */
    fun classPool(): ClassPool = ClassPool().apply {
        (classpath + bootClasspath).filter { it.exists() }.forEach { appendClassPath(it.absolutePath) }
    }

    /** A class was redefined on the device: [bytecode] before transformations */
    fun redefined(classId: ClassId, bytecode: ByteArray, lambdas: LambdaClassState? = lambdaState(classId)) {
        definitions[classId] = Definition(bytecode, lambdas)
    }

    /** A new class was added to the application: Upstream tracks it once loaded ('classBeingRedefined == null') */
    fun added(classId: ClassId, bytecode: ByteArray, lambdas: LambdaClassState? = null) {
        definitions[classId] = Definition(bytecode, lambdas)
        track(classId, bytecode)?.let { currentApplicationInfo.add(it) }
    }

    /** Equivalent of upstream 'enqueueRuntimeAnalysis' for a class being redefined */
    fun enqueueRedefinition(
        classId: ClassId, bytecode: ByteArray, destination: MutableApplicationInfo = pendingRedefinitions
    ) {
        track(classId, bytecode)?.let { destination.add(it) }
    }

    private fun index(classpath: List<File>) {
        val entries = mutableListOf<ByteArray>()
        classpath.forEach { file ->
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
                    entries += classFile.readBytes()
                }
                file.isFile && (file.extension == "jar" || file.extension == "zip") -> ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                        .forEach { entry -> entries += zip.getInputStream(entry).use { it.readBytes() } }
                }
            }
        }

        /* Parsing 'ClassInfo' dominates: do it in parallel, index sequentially */
        val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
        try {
            entries.map { code ->
                executor.submit<Triple<ClassId, ByteArray, ClassInfo?>?> {
                    val classId = ClassId(code) ?: return@submit null
                    Triple(classId, code, track(classId, code))
                }
            }.forEach { future ->
                val (classId, code, info) = future.get() ?: return@forEach
                /* First classpath entry wins */
                if (definitions.putIfAbsent(classId, Definition(code)) != null) return@forEach
                if (info != null) currentApplicationInfo.add(info)
            }
        } finally {
            executor.shutdown()
        }
    }

    private fun track(classId: ClassId, bytecode: ByteArray): ClassInfo? =
        UpstreamAnalysis.track(classId, bytecode)
}
