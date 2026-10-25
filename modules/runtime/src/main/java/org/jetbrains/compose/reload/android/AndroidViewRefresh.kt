package org.jetbrains.compose.reload.android

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import java.lang.reflect.InvocationTargetException
import java.util.IdentityHashMap
import org.jetbrains.compose.reload.android.protocol.Protocol

/**
 * Compose's Updater compares update lambdas by identity. Redefinition preserves that identity,
 * so even a forced recomposition can leave an AndroidView displaying the old method's output.
 * After composition/layout, refresh only existing holders whose update setter did not run.
 * Use the holder's setter to retain its snapshot observation and attachment checks.
 */
internal fun refreshAndroidViewsAfterRecompose(loader: ClassLoader) {
    val holder = try {
        loader.loadClass("androidx.compose.ui.viewinterop.ViewFactoryHolder")
    } catch (_: ClassNotFoundException) {
        return // An application can use Compose without View interop.
    }
    val getUpdate: java.lang.reflect.Method
    val getBlock: java.lang.reflect.Method
    val setBlock: java.lang.reflect.Method
    try {
        getUpdate = holder.getMethod("getUpdate")
        getBlock = holder.getMethod("getUpdateBlock")
        setBlock = holder.getMethod("setUpdateBlock", loader.loadClass("kotlin.jvm.functions.Function1"))
    } catch (missing: ReflectiveOperationException) {
        Log.w(TAG, "AndroidView refresh API unavailable; keeping existing views", missing)
        return
    }
    WindowInspector.getGlobalWindowViews().forEach { root ->
        val updates = IdentityHashMap<View, Any>()
        fun collect(view: View) {
            if (holder.isInstance(view)) updates[view] = getUpdate.invoke(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(root)
        if (updates.isEmpty()) return@forEach
        val observer = root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            fun dispose() {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                root.removeOnAttachStateChangeListener(this)
            }
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = dispose()
            override fun onPreDraw(): Boolean {
                dispose()
                updates.forEach { (view, original) ->
                    if (!view.isAttachedToWindow || getUpdate.invoke(view) !== original) return@forEach
                    try {
                        setBlock.invoke(view, getBlock.invoke(view))
                    } catch (failure: InvocationTargetException) {
                        val cause = failure.cause ?: failure
                        Log.e(TAG, "AndroidView update failed after reload", cause)
                        AndroidHotReloadRuntime.send(Protocol.UIException(cause.message, Protocol.StackFrame.of(cause)))
                    }
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
        root.addOnAttachStateChangeListener(listener)
        root.invalidate()
    }
}
