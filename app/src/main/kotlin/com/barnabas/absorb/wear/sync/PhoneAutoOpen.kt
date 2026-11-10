package com.barnabas.absorb.wear.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.barnabas.absorb.wear.AbsorbWearApp
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Brings the app up on the phone-remote screen when the phone starts
 * playing, for anyone who would rather land here than in the system media
 * controls. The watch may not open its own screen from the background, so
 * it asks the phone, which has Wear OS open it (RemoteActivityHelper).
 * Off unless turned on in Settings.
 */
object PhoneAutoOpen {
    private const val TAG = "AbsorbWearPlayer"
    private const val PREFS = "absorb_wear_playback"
    private const val KEY_ENABLED = "open_on_phone_play"

    /** The book the phone was last seen playing, empty while it wasn't.
     *  Kept on disk because Wear OS often restarts the process between
     *  pushes, and a lost "was playing" would read as a fresh start. */
    private const val KEY_PHONE_PLAYING = "phone_playing_item"

    /** A push older than this is the phone's last state replayed on
     *  reconnect, not a play that just started. */
    private const val FRESH_MS = 30_000L

    /** A play the watch itself asked for (its buttons, the complications,
     *  the notification) shouldn't throw the app open. */
    private const val AFTER_WATCH_COMMAND_MS = 10_000L
    private const val MIN_GAP_MS = 20_000L

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var askedAt = 0L

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
    }

    fun set(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    /** Each state the phone pushes. Asks the phone to open the app when it
     *  went from not playing (or another book) to playing. */
    fun onPhoneState(context: Context, state: PlayerState) {
        val p = prefs ?: return
        val playing = if (state.hasBook && state.isPlaying) state.itemId ?: "?" else ""
        if (playing == p.getString(KEY_PHONE_PLAYING, "")) return
        p.edit().putString(KEY_PHONE_PLAYING, playing).apply()
        if (playing.isEmpty() || !_enabled.value) return

        val now = System.currentTimeMillis()
        val skip = when {
            now - state.issuedAt > FRESH_MS -> "old state"
            AbsorbWearApp.isInForeground -> "app already open"
            PlaybackManager.uiState.value.hasItem -> "the watch has its own book"
            now - PlayerCommandClient.lastCommandAt < AFTER_WATCH_COMMAND_MS -> "started from the watch"
            now - askedAt < MIN_GAP_MS -> "asked moments ago"
            else -> null
        }
        if (skip != null) {
            Log.i(TAG, "phone started playing $playing, not opening: $skip")
            return
        }
        askedAt = now
        Log.i(TAG, "phone started playing $playing, asking it to open the app")
        val commands = PlayerCommandClient(context.applicationContext)
        scope.launch { commands.openWatchApp() }
    }
}
