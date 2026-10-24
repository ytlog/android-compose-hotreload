package org.jetbrains.compose.reload.android.host.reload

import org.jetbrains.compose.reload.android.host.session.ApplicationIndex
import org.jetbrains.compose.reload.android.host.session.ResourceIndex
import org.jetbrains.compose.reload.android.host.upstream.UpstreamAgent
import org.jetbrains.compose.reload.android.host.device.Device
import org.jetbrains.compose.reload.android.host.dex.Dexer
import org.jetbrains.compose.reload.android.host.dex.prepareLambdas
import org.jetbrains.compose.reload.android.host.dex.lambdaSites
import org.jetbrains.compose.reload.android.host.dex.readClass
import org.jetbrains.compose.reload.android.host.dex.readLambdaAccessors

import javassist.ClassPool
import org.jetbrains.compose.reload.analysis.ClassId
import org.jetbrains.compose.reload.analysis.ResolvedDirtyScopes
import org.jetbrains.compose.reload.analysis.classInitializerMethodId
import org.jetbrains.compose.reload.analysis.isClassInitializer
import org.jetbrains.compose.reload.android.protocol.Protocol
import org.jetbrains.compose.reload.core.Context
import org.jetbrains.compose.reload.core.HotReloadEnvironment
import org.jetbrains.compose.reload.core.StaticsReinitializeMode
import org.jetbrains.compose.reload.core.Try
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.debug
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.exception
import org.jetbrains.compose.reload.core.isClass
import org.jetbrains.compose.reload.core.sortedByTopology
import org.jetbrains.compose.reload.core.warn
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val logger = createLogger()

/** A redefinition prepared on the host, executed by the device */
private class Definition(
    val classId: ClassId,
    /** Bytecode as compiled */
    val original: ByteArray,
    /** Bytecode as sent to the device (transformed for statics initialization) */
    val bytecode: ByteArray,
    /** The class is not part of the running application yet */
    val isNew: Boolean,
    /** upstream: only redefined for statics re-initialization when the class is loaded and not transformed yet */
    val staticsOnly: Boolean = false,
)


internal enum class ClassChange { Added, Modified, Removed }

private val reloadGeneration = AtomicInteger(0)

/**
 * Port of the upstream agent's 'reload.kt' (and 'staticsInitialization.kt').
 * 'Instrumentation.redefineClasses' and the statics re-initialization are executed by the device (on its UI thread).
 */
internal fun Context.reload(
    index: ApplicationIndex, dexer: Dexer, device: Device,
    pendingChanges: Map<File, ClassChange>,
    resources: ResourceIndex,
    onDeviceRequest: (String) -> Unit,
): Try<ResolvedDirtyScopes> = Try {
    // Validate before adding/redefining any classes, including mixed code/resource edits.
    val resourceApk = resources.pending()
    val classPool = index.classPool()

    val definitions = pendingChanges.mapNotNull { (file, change) ->
        if (change == ClassChange.Removed) {
            logger.debug("Removed: $file")
            return@mapNotNull null
        }

        logger.debug("${change.name}:  $file")

        if (!file.isClass()) {
            return@mapNotNull null
        }

        if (!file.isFile) {
            logger.warn("$change: $file is not a regular file")
            return@mapNotNull null
        }

        logger.debug("Loading: $file")
        val code = file.readBytes()

        val classId = ClassId(code)
        if (classId == null) {
            logger.error("Cannot infer 'ClassId' for '$file'")
            return@mapNotNull null
        }

        /*
        Upstream skips classes which are not loaded yet: the JVM will load them from the 'hot' classpath.
        ART loads application classes from the installed APK: new classes are added to the application class loader.
         */
        if (!index.isKnown(classId)) {
            logger.debug("Class '$classId' is not part of the installed application: adding")
            return@mapNotNull Definition(classId, code, code, isNew = true)
        }

        /*
        Redefining a class with its current bytecode has no effect (on the JVM).
        Skip unchanged definitions so reverting a rejected structural change can recover.
         */
        if (index.bytecode(classId)?.contentEquals(code) == true) {
            logger.debug("Unchanged: '$classId'")
            return@mapNotNull null
        }

        val transformed = runCatching {
            val clazz = classPool.makeClass(code.inputStream())
            UpstreamAgent.transformForStaticsInitialization(clazz)
            clazz.toBytecode().also { clazz.detach() }
        }.getOrElse { failure ->
            logger.error("Failed to transform '$classId'", failure)
            code
        }

        Definition(classId, code, transformed, isNew = false)
    }

    val attempt = ReloadAttempt(index)
    definitions.filter { !it.isNew }.forEach { definition ->
        attempt.enqueue(definition.classId, definition.bytecode)
    }
    val dirty = attempt.analyze(this)

    val statics = resolveStaticsReinitialization(index, classPool, definitions, dirty)
    val allDefinitions = definitions + statics.additionalDefinitions

    // Seed from the installed snapshot once. Current-pass accessors cannot describe cached old closures.
    val generation = reloadGeneration.incrementAndGet()
    val seeds = allDefinitions.mapNotNull { definition ->
        index.bytecode(definition.classId)?.takeIf {
            index.lambdaState(definition.classId) == null && readClass(it).lambdaSites().isNotEmpty()
        }?.let { definition.classId to it }
    }.toMap()
    val installedDex = dexer.dex(seeds)
    val plans = allDefinitions.associate { definition ->
        val initial = seeds[definition.classId]
        val accessors = installedDex[definition.classId.toFqn()]
            ?.let { readLambdaAccessors(it, definition.classId.value) }.orEmpty()
        definition.classId to prepareLambdas(definition.classId, definition.original, definition.bytecode,
            generation, index.lambdaState(definition.classId), initial, accessors)
    }
    val dex = dexer.dex(plans.values.flatMap { it.classes.entries }.associate { it.key to it.value })
    val staticsOnly = statics.additionalDefinitions.mapTo(HashSet()) { it.classId.toFqn() }

    val groups = dirty.dirtyScopes.mapNotNull { it.group?.key }.distinct().toIntArray()
    val deviceRequestId = UUID.randomUUID().toString()
    onDeviceRequest(deviceRequestId)
    val result = device.reload(
        Protocol.Reload(
            deviceRequestId,
            dex.map { (className, bytes) ->
                /* Synthetic classes created by D8 follow their primary class */
                val primary = className.substringBefore("$$")
                Protocol.ClassDefinition(className, bytes, primary in staticsOnly)
            },
            statics.reinitialize.map { it.toFqn() },
            groups,
            resourceApk,
        )
    )

    if (result.classesAdded || result.success) {
        definitions.filter { it.isNew }.forEach { index.added(it.classId, it.original, plans.getValue(it.classId).state) }
    }

    if (result.classesRedefined || result.success) {
        attempt.commit()
        definitions.filter { !it.isNew }.forEach { definition ->
            index.redefined(definition.classId, definition.original, plans.getValue(definition.classId).state)
        }
        /* upstream: statics-only redefinitions are recorded as pending redefinitions for the next reload */
        statics.additionalDefinitions.forEach {
            // The device may skip a statics-only owner (unloaded/already transformed).
            // Its source has not evolved: entries/IDs are deterministic from the same original snapshot.
            // Keep the prior inventory (or seed it again), never claim a skipped owner was installed.
            index.enqueueRedefinition(it.classId, it.bytecode)
        }
    }

    if (!result.success) {
        throw DeviceReloadException(result)
    }

    resourceApk?.let(resources::commit)

    dirty
}

private class StaticsReinitialization(
    val additionalDefinitions: List<Definition>,
    /** In execution order */
    val reinitialize: List<ClassId>,
)

/** Port of upstream 'reinitializeStaticsIfNecessary': which classes re-run their class initializer and in which order */
private fun resolveStaticsReinitialization(
    index: ApplicationIndex, classPool: ClassPool,
    definitions: List<Definition>, dirty: ResolvedDirtyScopes
): StaticsReinitialization {
    /* Step 1: First, let us identify which clinits actually changed and shall be re-executed */
    val dirtyClasses = when (HotReloadEnvironment.staticsReinitializeMode) {
        StaticsReinitializeMode.ChangedOnly -> definitions.filter { !it.isNew }.mapNotNull { definition ->
            definition.classId.takeIf { it.classInitializerMethodId in dirty.dirtyMethodIds }
        }
        StaticsReinitializeMode.AllDirty -> dirty.dirtyMethodIds.keys.filter { it.isClassInitializer }
            .map { it.classId }.filter { classId ->
                if (!index.isKnown(classId)) {
                    logger.debug("Class '$classId' is not loaded yet")
                    return@filter false
                }
                true
            }
    }.distinct()

    if (dirtyClasses.isEmpty()) {
        logger.debug("No class initializers were changed")
        return StaticsReinitialization(emptyList(), emptyList())
    }

    /*
    Transform classes for re-initialization that are not transformed yet.
    Classes of this reload are transformed already. Whether other classes are transformed already
    is checked by the device on the loaded class (upstream: 'clazz.getDeclaredMethod(reinitializeName)')
     */
    val redefinedInThisReload = definitions.associateBy { it.classId }
    val toRedefine = dirtyClasses.mapNotNull { classId ->
        if (classId in redefinedInThisReload) return@mapNotNull null

        runCatching {
            val bytecode = index.bytecode(classId) ?: error("Missing bytecode for '$classId'")
            val transformedClass = classPool.makeClass(bytecode.inputStream())
            UpstreamAgent.transformForStaticsInitialization(transformedClass)
            Definition(classId, bytecode, transformedClass.toBytecode(), isNew = false, staticsOnly = true)
                .also { transformedClass.detach() }
        }.getOrElse { failure ->
            logger.error("Failed to transform '$classId'", failure)
            null
        }
    }
    if (toRedefine.isNotEmpty()) {
        if (HotReloadEnvironment.staticsReinitializeMode == StaticsReinitializeMode.ChangedOnly) {
            logger.warn("Expected for changed classes to be already transformed for statics initialization")
        }
    }

    val dirtyClassIds = dirtyClasses.toHashSet()
    val classInitializerDependencies = dirtyClasses.associateWith { classId ->
        dirty.dirtyMethodIds[classId.classInitializerMethodId].orEmpty()
            .flatMap { scope -> scope.methodDependenciesList }
            .mapNotNull { dependencyMethodId -> dependencyMethodId.classId.takeIf { it in dirtyClassIds && it != classId } }
    }

    /* Step 2: Let us ensure we're executing the initializers in a reasonable order: if class 'A' depends on 'B' */
    val topologicallySortedDirtyClasses = dirtyClasses.sortedByTopology(
        onCycle = { cycle -> logger.warn("<clinit> cycle detected: ${cycle.joinToString(", ") { it.toFqn() }}") },
        edges = { classId -> classInitializerDependencies[classId].orEmpty() }
    )

    /* Execute the re-initialization in reverse order (dependencies first) */
    return StaticsReinitialization(toRedefine, topologicallySortedDirtyClasses.asReversed())
}

/** A failure reported by the device runtime, carrying the device stack trace */
internal class DeviceReloadException(result: Protocol.ReloadResult) : Exception(result.errorMessage) {
    init {
        stackTrace = result.errorStacktrace.orEmpty().map { frame ->
            StackTraceElement(frame.className, frame.methodName, frame.fileName, frame.lineNumber)
        }.toTypedArray()
    }
}
