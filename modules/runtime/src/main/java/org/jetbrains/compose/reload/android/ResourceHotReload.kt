package org.jetbrains.compose.reload.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/** One shared loader updates application resources and both current and future activities. UI thread only. */
internal object ResourceHotReload {
    private val loader = ResourcesLoader()

    fun initialize(application: Application) {
        application.resources.addLoaders(loader)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.resources.addLoaders(loader)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Parse before touching classes. The provider duplicates the descriptor; the temporary APK can be unlinked. */
    fun prepare(directory: File, bytes: ByteArray): ResourcesProvider {
        val apk = File.createTempFile("compose-resources-", ".apk", directory)
        try {
            apk.writeBytes(bytes)
            return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use {
                ResourcesProvider.loadFromApk(it)
            }
        } finally {
            apk.delete()
        }
    }

    fun apply(provider: ResourcesProvider) {
        val old = loader.providers
        loader.setProviders(listOf(provider))
        old.forEach { it.close() }
        Log.d(TAG, "Updated Android resource provider")
    }
}

/** A fresh identity invalidates Compose resource/painter remembers without disposing application remember state. */
@Suppress("DEPRECATION")
internal fun resourceContext(base: Context, version: Int): Context {
    val source = base.resources
    val resources = Resources(source.assets, source.displayMetrics, source.configuration)
    val theme = resources.newTheme().apply {
        setTo(base.theme)
        rebase()
        // Android Theme.equals compares the applied style sequence, not object identity.
        // Encode the generation with an empty style so Compose's painter caches get a distinct
        // theme key without changing any theme attributes. ThemeKey deduplicates repeated
        // (style, force) pairs, so each bit needs its own marker. Bounded by 32 empty styles.
        var generation = version
        val markers = resources.obtainTypedArray(R.array.compose_hot_reload_resource_markers)
        try {
            var bit = 0
            while (generation != 0) {
                applyStyle(markers.getResourceId(bit++, 0), generation and 1 != 0)
                generation = generation ushr 1
            }
        } finally {
            markers.recycle()
        }
    }
    return object : ContextWrapper(base) {
        override fun getResources(): Resources = resources
        override fun getAssets() = resources.assets
        override fun getTheme(): Resources.Theme = theme
    }
}
