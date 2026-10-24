package org.jetbrains.compose.reload.android

import android.util.Log
import org.jetbrains.compose.reload.android.protocol.Protocol
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** upstream agent 'reinitializeName' (staticsInitialization.kt) */
private const val REINITIALIZE_NAME = "\$chr\$clinit"

private val findLoadedClass by lazy {
    ClassLoader::class.java.getDeclaredMethod("findLoadedClass", String::class.java).apply { isAccessible = true }
}

private fun ClassLoader.isLoaded(className: String): Boolean = findLoadedClass.invoke(this, className) != null

/**
 * Device side of the upstream agent's 'reloadRequestHandler.kt' / 'reload.kt' / 'staticsInitialization.kt'.
 *
 * Executed on the UI thread, like upstream, to ensure Compose does not render or work in an inconsistent state:
 * add new classes -> redefine classes -> re-initialize statics -> invalidate Compose groups.
 */
internal fun executeReload(loader: ClassLoader, codeCacheDir: File, reload: Protocol.Reload): Protocol.ReloadResult =
    runOnUiThreadBlocking {
        var classesAdded = false
        var classesRedefined = false
        var resourcesChanged = false
        var resourceProvider: android.content.res.loader.ResourcesProvider? = null

        val result = runCatching {
            resourceProvider = reload.resourceApk?.let { ResourceHotReload.prepare(codeCacheDir, it) }
            /* Upstream only redefines and re-initializes classes which are loaded: capture before loading anything */
            val names = reload.definitions.map { it.className } + reload.reinitializeStatics
            val loadedBeforeReload = names.filterTo(HashSet()) { loader.isLoaded(it) }

            val redefinedClasses = mutableListOf<Class<*>>()
            val redefinitions = mutableListOf<ByteArray>()
            val additions = mutableListOf<ByteArray>()

            reload.definitions.forEach { definition ->
                if (definition.staticsOnly) {
                    /* upstream: only loaded classes, which are not transformed for re-initialization yet */
                    if (definition.className !in loadedBeforeReload) return@forEach
                    if (Class.forName(definition.className, false, loader).hasReinitializer()) return@forEach
                }

                /* ART loads application classes from the installed APK: classes which are not loaded yet are redefined as well */
                val clazz = try {
                    Class.forName(definition.className, false, loader)
                } catch (_: ClassNotFoundException) {
                    null
                }

                if (clazz == null) {
                    additions += definition.dex
                } else {
                    redefinedClasses += clazz
                    redefinitions += definition.dex
                }
            }

            /* art-dcevm validates admission in RedefineClasses; capabilities are diagnostic only. */
            if (additions.isNotEmpty()) {
                addClasses(loader, codeCacheDir, additions)
                classesAdded = true
            }

            if (redefinedClasses.isNotEmpty()) {
                /* Correlates ART's log-only failures with this request when lastRejection asks for logcat. */
                Log.d(TAG, "RedefineClasses request ${reload.reloadRequestId}: ${redefinedClasses.joinToString { it.name }}")
                JvmtiAgent.redefineClasses(redefinedClasses, redefinitions)
                classesRedefined = true
                Log.d(TAG, "Redefined ${redefinedClasses.size} classes")
            }

            // Class admission failure leaves the previous provider intact. Statics may read the new resources.
            resourceProvider?.let {
                ResourceHotReload.apply(it)
                resourceProvider = null // ownership transferred to the shared loader
                resourcesChanged = true
            }

            reload.reinitializeStatics.forEach { className ->
                if (className !in loadedBeforeReload) return@forEach
                Class.forName(className, false, loader).reinitializeStaticsIfNecessary()
            }

            /* upstream 'compose.kt': invalidate the groups after a successful reload */
            reload.invalidatedGroupKeys.forEach { key ->
                Log.d(TAG, "Invalidating group '$key'")
                invalidateGroupsWithKey(loader, key)
            }
            if (reload.recomposedGroupKeys.isNotEmpty()) {
                recomposeUnchangedGroups(loader, reload.recomposedGroupKeys)
            }
        }
        resourceProvider?.close()

        /* upstream 'executeAfterHotReloadListeners' is invoked for successful and failed reloads */
        onHotReload(reload.reloadRequestId, result.exceptionOrNull(), resourcesChanged)

        result.fold(
            onSuccess = {
                Protocol.ReloadResult(reload.reloadRequestId, true, classesAdded, classesRedefined, null, null)
            },
            onFailure = { failure ->
                Log.e(TAG, "Reload failed", failure)
                Protocol.ReloadResult(
                    reload.reloadRequestId, false, classesAdded, classesRedefined,
                    failure.message ?: failure.javaClass.simpleName, Protocol.StackFrame.of(failure)
                )
            }
        )
    }

private fun Class<*>.hasReinitializer(): Boolean = runCatching { getDeclaredMethod(REINITIALIZE_NAME) }.isSuccess

/** Port of upstream 'Class.reinitializeStaticsIfNecessary' */
private fun Class<*>.reinitializeStaticsIfNecessary() {
    val reinit = runCatching { getDeclaredMethod(REINITIALIZE_NAME) }.getOrNull() ?: return
    Log.d(TAG, "Re-initializing '$name'")
    reinit.isAccessible = true
    try {
        reinit.invoke(null)
    } catch (e: InvocationTargetException) {
        val targetException = e.targetException ?: e
        throw RuntimeException("Failed to re-initialize statics of class '$name': ${targetException.message}", targetException)
    }
    Log.d(TAG, "Re-initialized '$name'")
}

/**
 * New classes are appended to the application class loader.
 * Android 14+ only loads read-only dex files: written through a file descriptor opened before marking it read-only.
 */
private fun addClasses(loader: ClassLoader, codeCacheDir: File, dexes: List<ByteArray>) {
    val jar = File.createTempFile("compose-hot-reload-", ".jar", codeCacheDir)
    try {
        jar.outputStream().use { stream ->
            check(jar.setReadOnly()) { "Failed to mark '$jar' read-only" }
            ZipOutputStream(stream).use { zip ->
                dexes.forEachIndexed { index, dex ->
                    zip.putNextEntry(ZipEntry(if (index == 0) "classes.dex" else "classes${index + 1}.dex"))
                    zip.write(dex)
                    zip.closeEntry()
                }
            }
        }
        JvmtiAgent.addToDexClassLoader(loader, jar.absolutePath)
        Log.d(TAG, "Added ${dexes.size} classes")
    } catch (t: Throwable) {
        jar.delete()
        throw t
    }
}
