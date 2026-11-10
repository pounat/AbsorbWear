package com.barnabas.absorb.wear.player

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/**
 * Where watch audio can go.
 *
 * Wear OS does not play media through the built-in speaker. Without
 * Bluetooth headphones or a Bluetooth speaker connected, ExoPlayer (with
 * suppression turned on in the media service) holds playback instead of
 * playing into nothing, and the user is sent to the system output picker.
 * The intents here are the ones Media3's own Wear listener uses: the
 * Wear OS 5 media output panel, falling back to the Bluetooth settings page
 * filtered to audio devices on older watches.
 */
object AudioOutput {

    private const val TAG = "AbsorbWearPlayback"
    private const val OUTPUT_SWITCHER_ACTION = "com.android.settings.panel.action.MEDIA_OUTPUT"
    private const val OUTPUT_SWITCHER_EXTRA_PACKAGE = "com.android.settings.panel.extra.PACKAGE_NAME"
    private const val BLUETOOTH_FILTER_TYPE_AUDIO = 1

    private val suitableTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    /** True when a Bluetooth audio device is connected right now. */
    fun hasSuitableOutput(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) return false
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in suitableTypes }
    }

    /** The connected Bluetooth audio device's name, or null without one. */
    fun outputName(context: Context): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) return null
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in suitableTypes } ?: return null
        return device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth audio"
    }

    /** Open the system picker for where media should play. */
    fun launchOutputSwitcher(context: Context) {
        val switcher = Intent(OUTPUT_SWITCHER_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(OUTPUT_SWITCHER_EXTRA_PACKAGE, context.packageName)
        if (startSystemActivity(context, switcher)) return

        val bluetooth = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
            .putExtra("EXTRA_CONNECTION_ONLY", true)
            .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", BLUETOOTH_FILTER_TYPE_AUDIO)
        if (!startSystemActivity(context, bluetooth)) {
            Log.w(TAG, "No system output picker or Bluetooth settings page to open")
        }
    }

    /** Only a system app may answer these intents, same rule as Media3. */
    private fun startSystemActivity(context: Context, intent: Intent): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val target = context.packageManager.queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo.applicationInfo.flags and systemFlags != 0 }
            ?: return false
        return try {
            context.startActivity(
                intent.setClassName(target.activityInfo.packageName, target.activityInfo.name),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the output picker", e)
            false
        }
    }
}
