package com.barnabas.absorb.wear.sleep

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityState
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Pauses the book when the watch says its wearer is asleep. Health Services
 * reports the user activity state in the background; on the change to
 * asleep the watch's own player pauses and rewinds to where the book was
 * when sleep began, or the phone is told so its sleep timer does the same.
 * Playing again soon after counts as a wrong call: the rewind is undone and
 * the watch keeps quiet for a while.
 */
object SleepWatch {
    private const val TAG = "AbsorbWearSleep"
    private const val PREFS = "absorb_wear_sleep"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST = "last_event"

    /** Resuming within this long of the pause counts as awake all along. */
    private const val UNDO_WINDOW_MS = 5 * 60_000L

    /** After a wrong call the watch waits this long before pausing again. */
    private const val COOLDOWN_MS = 45 * 60_000L

    /** The most the rewind reaches back for time spent asleep. */
    private const val MAX_ASLEEP_REWIND_SEC = 30 * 60

    /** On top of the time asleep, since sleep sets in before the watch notices. */
    private const val EXTRA_REWIND_SEC = 30

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** What happened last, for the settings screen. */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    private var lastState: UserActivityState? = null
    private var ignoreUntilMs = 0L

    // The last pause this made on the watch, for the undo.
    private var pausedAtMs = 0L
    private var pausedItemId: String? = null
    private var rewoundFromSec = 0.0
    private var rewoundToSec = 0.0

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _lastEvent.value = prefs.getString(KEY_LAST, null)
        Log.i(TAG, "pause when asleep is ${if (_enabled.value) "on" else "off"}")
        if (_enabled.value) register()
    }

    fun setEnabled(on: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        Log.i(TAG, "pause when asleep turned ${if (on) "on" else "off"}")
        if (on) register() else unregister()
    }

    private fun register() {
        val client = HealthServices.getClient(appContext).passiveMonitoringClient
        val executor = ContextCompat.getMainExecutor(appContext)
        val caps = client.getCapabilitiesAsync()
        caps.addListener({
            try {
                Log.i(TAG, "Health Services capabilities: ${caps.get()}")
            } catch (e: Exception) {
                Log.w(TAG, "capabilities unreadable", e)
            }
        }, executor)
        val config = PassiveListenerConfig.builder()
            .setShouldUserActivityInfoBeRequested(true)
            .build()
        val future = client.setPassiveListenerServiceAsync(SleepListenerService::class.java, config)
        future.addListener({
            try {
                future.get()
                Log.i(TAG, "watching for sleep")
                note("Watching for sleep")
            } catch (e: Exception) {
                Log.w(TAG, "could not start watching for sleep", e)
                note("Not available: ${e.cause?.message ?: e.message}")
            }
        }, executor)
    }

    private fun unregister() {
        val future = HealthServices.getClient(appContext).passiveMonitoringClient.clearPassiveListenerServiceAsync()
        future.addListener({
            try {
                future.get()
                Log.i(TAG, "stopped watching for sleep")
            } catch (e: Exception) {
                Log.w(TAG, "could not stop watching for sleep", e)
            }
        }, ContextCompat.getMainExecutor(appContext))
        note(null)
    }

    private fun note(text: String?) {
        _lastEvent.value = text
        prefs.edit().putString(KEY_LAST, text).apply()
    }

    private fun stamp(ms: Long): String = clock.format(Date(ms))

    /** A user activity report from Health Services. */
    fun onActivity(state: UserActivityState, changedAtMs: Long) {
        if (!::prefs.isInitialized) return
        val now = System.currentTimeMillis()
        Log.i(TAG, "activity $state since ${stamp(changedAtMs)} (${(now - changedAtMs) / 60_000} min ago)")
        val was = lastState
        lastState = state
        if (state != UserActivityState.USER_ACTIVITY_ASLEEP) {
            if (was == UserActivityState.USER_ACTIVITY_ASLEEP) note("Awake at ${stamp(changedAtMs)}")
            return
        }
        if (!_enabled.value) {
            Log.i(TAG, "asleep, but pausing is off")
            return
        }
        if (was == UserActivityState.USER_ACTIVITY_ASLEEP) {
            Log.i(TAG, "still asleep, already handled")
            return
        }
        if (now < ignoreUntilMs) {
            Log.i(TAG, "asleep, but holding off until ${stamp(ignoreUntilMs)} after the last wrong call")
            note("Asleep at ${stamp(changedAtMs)}, held off")
            return
        }
        scope.launch { onAsleep(changedAtMs, now) }
    }

    private suspend fun onAsleep(sinceMs: Long, nowMs: Long) {
        val ui = PlaybackManager.uiState.value
        val asleepSec = ((nowMs - sinceMs) / 1000).coerceIn(0L, MAX_ASLEEP_REWIND_SEC.toLong()).toInt()
        val itemId = ui.itemId
        if (ui.isPlaying && itemId != null) {
            val from = ui.positionMs / 1000.0
            val rewind = asleepSec + EXTRA_REWIND_SEC
            val to = (from - rewind).coerceAtLeast(0.0)
            Log.i(TAG, "asleep since ${stamp(sinceMs)}: pausing the watch at ${from.toInt()}s and rewinding ${rewind}s to ${to.toInt()}s")
            PlaybackManager.playPause()
            PlaybackManager.seekToSec(to)
            pausedAtMs = nowMs
            pausedItemId = itemId
            rewoundFromSec = from
            rewoundToSec = to
            note("Paused the watch at ${stamp(nowMs)}, asleep since ${stamp(sinceMs)}")
            tellPhone(sinceMs, handled = true)
            return
        }
        val phone = PlayerStateStore.state.value
        val phoneWord = if (phone?.isPlaying == true) "playing" else "not playing or unknown"
        Log.i(TAG, "asleep since ${stamp(sinceMs)}: the watch is not playing, the phone is $phoneWord; telling the phone")
        note("Asleep at ${stamp(sinceMs)}, told the phone")
        tellPhone(sinceMs, handled = false)
    }

    private suspend fun tellPhone(sinceMs: Long, handled: Boolean) {
        try {
            val sent = PlayerCommandClient(appContext).sleepDetected(sinceMs, handledOnWatch = handled)
            Log.i(TAG, if (sent) "the phone was told" else "the phone could not be reached")
        } catch (e: Exception) {
            Log.w(TAG, "telling the phone failed", e)
        }
    }

    /**
     * The watch's player started again. Soon after a sleep pause that means
     * the wearer was awake: the rewind is undone and the watch holds off for
     * a while.
     */
    fun onLocalResume() {
        val at = pausedAtMs
        if (at == 0L) return
        val now = System.currentTimeMillis()
        val ui = PlaybackManager.uiState.value
        pausedAtMs = 0L
        if (now - at > UNDO_WINDOW_MS) {
            Log.i(TAG, "resumed long after the sleep pause, keeping the rewind")
            return
        }
        if (ui.itemId != pausedItemId) {
            Log.i(TAG, "resumed another book, keeping the rewind")
            return
        }
        val pos = ui.positionMs / 1000.0
        if (abs(pos - rewoundToSec) > 2.0) {
            Log.i(TAG, "moved since the sleep pause, keeping the rewind")
            return
        }
        ignoreUntilMs = now + COOLDOWN_MS
        Log.i(
            TAG,
            "resumed ${(now - at) / 1000}s after the sleep pause: counted as awake, back to ${rewoundFromSec.toInt()}s, holding off until ${stamp(ignoreUntilMs)}",
        )
        note("Resumed at ${stamp(now)}, counted as awake")
        PlaybackManager.seekToSec(rewoundFromSec)
    }
}
