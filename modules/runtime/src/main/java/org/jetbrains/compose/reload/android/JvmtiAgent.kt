package org.jetbrains.compose.reload.android

import android.content.Context
import android.os.Debug
import android.util.Log
import com.github.ytlog.artdcevm.api.DcevmRuntime

/** The Android counterpart of 'java.lang.instrument.Instrumentation' (see 'agent.cc') */
internal object JvmtiAgent {
    private const val LIBRARY = "compose_hot_reload_android"

    fun attach(context: Context) {
        /* ART DCEVM lifts the restrictions of 'RedefineClasses': it must be installed before redefining */
        DcevmRuntime.initialize(context)
        Log.i(TAG, "ART DCEVM capabilities: ${DcevmRuntime.capabilities()}")
        System.loadLibrary(LIBRARY)
        Debug.attachJvmtiAgent("lib$LIBRARY.so", null, context.classLoader)
        check(nativeIsAttached()) { "JVMTI agent did not attach" }
    }

    fun redefineClasses(classes: List<Class<*>>, definitions: List<ByteArray>) {
        val errorCode = nativeRedefineClasses(classes.toTypedArray(), definitions.toTypedArray())
        if (errorCode != 0) {
            val capabilities = DcevmRuntime.capabilities()
            Log.e(TAG, "ART DCEVM capabilities after rejection: $capabilities")
            /* This is the current transaction's native reason, cleared by art-dcevm at its entry. */
            val reason = capabilities.optString("lastRejection")
                .ifBlank { capabilities.optString("diagnostic", "No native rejection reason available") }
            error("RedefineClasses failed: ${nativeErrorName(errorCode)} ($errorCode); $reason")
        }
        if (classes.any { it.isInterface }) {
            Log.i(TAG, "ART DCEVM capabilities after interface reload: ${DcevmRuntime.capabilities()}")
        }
    }

    fun addToDexClassLoader(loader: ClassLoader, path: String) {
        val error = nativeAddToDexClassLoader(loader, path)
        check(error == 0) { "add_to_dex_class_loader failed: ${nativeErrorName(error)} ($error)" }
    }

    @JvmStatic private external fun nativeIsAttached(): Boolean
    @JvmStatic private external fun nativeRedefineClasses(classes: Array<Class<*>>, definitions: Array<ByteArray>): Int
    @JvmStatic private external fun nativeAddToDexClassLoader(loader: ClassLoader, path: String): Int
    @JvmStatic private external fun nativeErrorName(error: Int): String
}
