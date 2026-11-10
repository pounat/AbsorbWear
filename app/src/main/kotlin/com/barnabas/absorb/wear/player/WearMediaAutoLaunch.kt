package com.barnabas.absorb.wear.player

import android.content.Context
import android.util.Log

/**
 * The Wear OS per-app "auto-launch for phone media" setting, through the
 * platform's Wear SDK (`com.google.wear.services.media.MediaManager`, API
 * 37). Reached by reflection so the app needs no compile-time SDK: the
 * classes live on the watch itself, and an older watch simply reports
 * [STATE_UNKNOWN].
 *
 * When the user turns this on for the app, Wear OS opens our activity (the
 * one declaring REMOTE_MEDIA_ACTIVITY) for the phone's media session instead
 * of its own Media Controls.
 */
object WearMediaAutoLaunch {

    private const val TAG = "AbsorbWearPlayer"

    const val STATE_UNKNOWN = 0
    const val STATE_DISABLED = 1
    const val STATE_ENABLED = 2

    private fun manager(context: Context): Any? = try {
        val sdk = Class.forName("com.google.wear.Sdk")
        val managerClass = Class.forName("com.google.wear.services.media.MediaManager")
        val m = sdk.getMethod("getWearManager", Context::class.java, Class::class.java)
            .invoke(null, context.applicationContext, managerClass)
        if (m == null) Log.i(TAG, "Wear SDK present but it has no MediaManager for this app")
        m
    } catch (e: Throwable) {
        Log.i(TAG, "No Wear media manager on this watch: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** One of the STATE_ constants. */
    fun state(context: Context): Int {
        val m = manager(context) ?: return STATE_UNKNOWN
        return try {
            val raw = m.javaClass.getMethod("getMediaAutoLaunchState").invoke(m)
            Log.i(TAG, "MediaManager.getMediaAutoLaunchState -> $raw (${m.javaClass.name})")
            (raw as? Int) ?: STATE_UNKNOWN
        } catch (e: Throwable) {
            Log.w(TAG, "getMediaAutoLaunchState failed", e)
            STATE_UNKNOWN
        }
    }

    /** Open the system screen where the user turns auto-launch on for us. */
    fun openSettings(context: Context): Boolean {
        val m = manager(context) ?: return false
        return try {
            (m.javaClass.getMethod("showMediaAutoLaunchSettings").invoke(m) as? Boolean) ?: false
        } catch (e: Throwable) {
            Log.w(TAG, "showMediaAutoLaunchSettings failed", e)
            false
        }
    }
}
