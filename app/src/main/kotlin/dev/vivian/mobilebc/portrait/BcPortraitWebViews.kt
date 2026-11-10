package dev.vivian.mobilebc.portrait

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

/** One idle rendering runtime, without a character or an Activity reference. Main thread only. */
internal class BcPortraitWebViews(context: Context, private val resources: BcPortraitResources) : ComponentCallbacks2 {
    private val application = context.applicationContext
    private var idle: BcPortraitWebView? = null

    init { application.registerComponentCallbacks(this) }

    fun acquire(): BcPortraitWebView {
        val cached = idle
        idle = null
        return cached?.takeUnless { it.isReleased } ?: BcPortraitWebView(application, resources)
    }

    fun recycle(view: BcPortraitWebView) {
        if (idle == null && view.prepareForReuse()) idle = view else view.release()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) onLowMemory()
    }

    override fun onLowMemory() {
        idle?.release()
        idle = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
