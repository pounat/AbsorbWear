package com.barnabas.absorb.wear.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.sleep.SleepWatch
import com.barnabas.absorb.wear.player.AbsorbMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Single source of truth for on-watch streaming playback.
 *
 * Owns no player itself — [AbsorbMediaService] creates the ExoPlayer (so it
 * can run as a foreground media service) and hands it here via [attach]. The
 * UI observes [uiState] and drives transport through this object; starting an
 * item opens an ABS play session, streams its tracks, resumes at the saved
 * position, and reports progress back every [SYNC_INTERVAL_MS].
 *
 * All entry points are fire-and-forget: work runs on this object's own
 * process-wide scope, never a screen's composition scope, so navigating
 * between screens can't cancel a session start or a progress sync mid-flight.
 */
object PlaybackManager {

    private const val TAG = "AbsorbWearPlayback"
    /** Progress goes to the server on every pause, stop and finish; while
     *  playing it also goes every so often as a safety net, and the position
     *  is written to disk every few seconds so a killed process loses
     *  nothing. Fewer radio wake-ups than a 15s loop, which matters on a
     *  watch. */
    private const val SYNC_INTERVAL_MS = 10 * 60_000L
    private const val LOCAL_SAVE_EVERY_TICKS = 5
    const val DEFAULT_SKIP_BACK_SEC = 10
    const val DEFAULT_SKIP_FORWARD_SEC = 30
    private const val KEY_SKIP_BACK = "skip_back_sec"
    private const val KEY_SKIP_FORWARD = "skip_forward_sec"

    /** The skip amounts, the same as the phone app's: adopted from every
     *  player state the phone pushes and kept across launches. */
    var skipBackSec: Int = DEFAULT_SKIP_BACK_SEC
        private set
    var skipForwardSec: Int = DEFAULT_SKIP_FORWARD_SEC
        private set

    /** The phone's playback settings, copied from its pushes so a book
     *  sounds the same from either device. The defaults are the phone's. */
    @kotlinx.serialization.Serializable
    data class Settings(
        val autoRewindEnabled: Boolean = true,
        val autoRewindMinSec: Double = 1.0,
        val autoRewindMaxSec: Double = 30.0,
        val autoRewindDelaySec: Double = 0.0,
        val autoRewindChapterBarrier: Boolean = false,
        val autoRewindSessionStart: Boolean = false,
        val skipChapterBarrier: Boolean = true,
        /** Times shown divided by the speed: how long it really takes. */
        val speedAdjustedTime: Boolean = true,
        /** Minutes a tap on the running sleep timer adds. */
        val sleepAddMinutes: Int = 5,
        /** The speeds the speed button steps through, the phone's saved set. */
        val speedPresets: List<Float> = DEFAULT_SPEED_PRESETS,
    )

    private const val KEY_SETTINGS = "phone_settings"
    private const val KEY_FOLLOW_PHONE = "follow_phone_settings"
    @Volatile
    var settings: Settings = Settings()
        private set
    /** For screens: the settings and skips as they stand. */
    val settingsFlow = MutableStateFlow(Settings())
    val skipsFlow = MutableStateFlow(DEFAULT_SKIP_BACK_SEC to DEFAULT_SKIP_FORWARD_SEC)
    /** Whether the phone's pushes set the playback settings here, or the
     *  watch keeps its own. */
    private val _followPhone = MutableStateFlow(true)
    val followPhone: StateFlow<Boolean> = _followPhone.asStateFlow()

    /** Only kept books play on the watch: streaming is a battery drain
     *  there, so with this on a book has to be downloaded first. */
    private const val KEY_DOWNLOADS_ONLY = "downloads_only"
    private val _downloadsOnly = MutableStateFlow(false)
    val downloadsOnly: StateFlow<Boolean> = _downloadsOnly.asStateFlow()

    fun setDownloadsOnly(only: Boolean) {
        _downloadsOnly.value = only
        prefs?.edit()?.putBoolean(KEY_DOWNLOADS_ONLY, only)?.apply()
    }

    /** True when [itemId] (or that podcast's [episodeId]) may not play here
     *  because it is not kept. */
    fun blockedByDownloadsOnly(itemId: String, episodeId: String? = null): Boolean =
        _downloadsOnly.value &&
            !com.barnabas.absorb.wear.download.BookDownloads.isDownloaded(com.barnabas.absorb.wear.download.keepKey(itemId, episodeId))

    fun setFollowPhone(follow: Boolean) {
        _followPhone.value = follow
        prefs?.edit()?.putBoolean(KEY_FOLLOW_PHONE, follow)?.apply()
    }

    fun adoptSettings(s: Settings) {
        if (!_followPhone.value) return
        applySettings(s)
    }

    /** The watch's own choice, when it is not following the phone. */
    fun setSettings(s: Settings) = applySettings(s)

    private fun applySettings(s: Settings) {
        if (s == settings) return
        settings = s
        settingsFlow.value = s
        prefs?.edit()?.putString(KEY_SETTINGS, pendingJson.encodeToString(Settings.serializer(), s))?.apply()
        Log.i(TAG, "playback settings: $s")
    }

    /** The phone's speed becomes the watch's, for the next book it plays;
     *  a book already playing here keeps the speed set on the watch. */
    fun adoptSpeed(speed: Float) {
        if (!_followPhone.value) return
        if (speed !in 0.25f..4f || abs(speed - _uiState.value.speed) < 0.01f) return
        if (_uiState.value.hasItem) return
        setSpeed(speed)
    }

    fun adoptSkips(back: Int, forward: Int) {
        if (!_followPhone.value) return
        setSkips(back, forward)
    }

    /** The watch's own skip amounts, when it is not following the phone. */
    fun setSkips(back: Int, forward: Int) {
        if (back !in 1..600 || forward !in 1..600) return
        if (back == skipBackSec && forward == skipForwardSec) return
        skipBackSec = back
        skipForwardSec = forward
        skipsFlow.value = back to forward
        prefs?.edit()?.putInt(KEY_SKIP_BACK, back)?.putInt(KEY_SKIP_FORWARD, forward)?.apply()
        _player.value?.let { pushState() }
    }

    private const val PREFS_NAME = "absorb_wear_playback"
    private const val KEY_SPEED = "speed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var creds: Credentials? = null
    private var sessionId: String? = null
    /** Absolute start time (seconds) of each track within the item. */
    private var trackStartsSec: DoubleArray = DoubleArray(0)
    /** Length (seconds) of each track, as declared by the server until the
     *  player has measured the file. */
    private var trackDurationsSec: DoubleArray = DoubleArray(0)
    private var chapters: List<Chapter> = emptyList()
    /** Wall-clock seconds actually listened since the last successful sync —
     *  accumulated by the 1s ticker, so seeks don't inflate or lose time. */
    private var listenedSinceSyncSec: Double = 0.0
    /** Last position we trust, for retry-after-error and the final flush. */
    private var lastKnownPositionSec: Double = 0.0
    private var tickerJob: Job? = null
    private var syncJob: Job? = null
    private var playJob: Job? = null
    private var stopServiceHook: (() -> Unit)? = null
    private var lastRequest: PlayArgs? = null
    private var lastStartMs = 0L
    private var focusRetried = false
    private var errorRetried = false
    private var appContext: Context? = null

    /** Where a tapped book plays, Spotify-style: here or on the phone.
     *  Remembered across launches. */
    enum class PlayTarget { WATCH, PHONE }

    /** The book the user tapped, whether or not the watch has loaded it:
     *  with the phone as the target nothing is loaded here until play. */
    data class Selection(val itemId: String, val episodeId: String?, val title: String?, val author: String?)

    private const val KEY_TARGET = "play_target"
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()
    private val _target = MutableStateFlow(PlayTarget.WATCH)
    val target: StateFlow<PlayTarget> = _target.asStateFlow()

    private val AUTO_RETRY_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )

    /** A progress save not yet on the server: the playing session's spot,
     *  rewritten every few seconds in case the process dies, or a final save
     *  that failed for want of a network. One per session, so a second book
     *  can't overwrite the first one's. */
    @kotlinx.serialization.Serializable
    private data class PendingSync(
        val serverUrl: String,
        val sessionId: String,
        val positionSec: Double,
        val listenedSec: Double,
        val durationSec: Double,
        val savedAt: Long = 0L,
    )

    private val pendingJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private const val KEY_PENDING_SYNCS = "pending_syncs"

    /** The single record older builds kept. It was never cleared after a
     *  final save that worked, so it is more likely stale than not and is
     *  dropped rather than sent. */
    private const val KEY_PENDING_SYNC_OLD = "pending_sync"
    private const val PENDING_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private val pendingLock = Any()
    private var pendingSaves: MutableList<PendingSync>? = null

    private fun pendingList(): MutableList<PendingSync> {
        pendingSaves?.let { return it }
        val raw = prefs?.getString(KEY_PENDING_SYNCS, null)
        val list = raw?.let {
            runCatching {
                pendingJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PendingSync.serializer()), it)
            }.getOrNull()
        }.orEmpty().toMutableList()
        return list.also { pendingSaves = it }
    }

    private fun writePending(list: List<PendingSync>) {
        val encoded = pendingJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(PendingSync.serializer()), list)
        prefs?.edit()?.putString(KEY_PENDING_SYNCS, encoded)?.apply()
    }

    private fun putPending(save: PendingSync) = synchronized(pendingLock) {
        val list = pendingList()
        list.removeAll { it.sessionId == save.sessionId }
        list += save
        writePending(list)
    }

    /** Forget [sessionId]'s save, or only [exactly] that one when a newer
     *  save for the session may have replaced it meanwhile. */
    private fun dropPending(sessionId: String, exactly: PendingSync? = null) = synchronized(pendingLock) {
        val list = pendingList()
        if (list.removeAll { it.sessionId == sessionId && (exactly == null || it == exactly) }) writePending(list)
    }

    private data class PlayArgs(
        val itemId: String,
        val episodeId: String?,
        val title: String?,
        val author: String?,
    )

    /** Call once from Application.onCreate — loads the persisted speed. */
    fun init(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).let { p ->
            skipBackSec = p.getInt(KEY_SKIP_BACK, DEFAULT_SKIP_BACK_SEC)
            skipForwardSec = p.getInt(KEY_SKIP_FORWARD, DEFAULT_SKIP_FORWARD_SEC)
            p.getString(KEY_SETTINGS, null)?.let { raw ->
                runCatching { pendingJson.decodeFromString(Settings.serializer(), raw) }.getOrNull()?.let { settings = it }
            }
            settingsFlow.value = settings
            skipsFlow.value = skipBackSec to skipForwardSec
            _followPhone.value = p.getBoolean(KEY_FOLLOW_PHONE, true)
            Log.i(TAG, "playback settings at start: $settings, skips ${skipBackSec}s/${skipForwardSec}s, following phone=${_followPhone.value}")
            _downloadsOnly.value = p.getBoolean(KEY_DOWNLOADS_ONLY, false)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TARGET, null)?.let { saved ->
            PlayTarget.entries.firstOrNull { it.name == saved }?.let { _target.value = it }
        }
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.remove(KEY_PENDING_SYNC_OLD)?.apply()
        AbsLocalPlayback.init(context)
        _uiState.update { it.copy(speed = prefs?.getFloat(KEY_SPEED, 1f) ?: 1f) }
        retryPendingSync()
    }

    /** Send the progress saves that could not be delivered earlier. Safe to
     *  call any time; a no-op when nothing is waiting or we are signed out. */
    fun retryPendingSync() {
        val c = CredentialStore.credentials.value ?: return
        if (!pendingInFlight.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        // The session playing right now keeps its own save and delivers it
        // on pause; only the others are sent.
        val playing = sessionId
        val due = synchronized(pendingLock) {
            val list = pendingList()
            val stale = list.filter { it.serverUrl != c.serverUrl || (it.savedAt > 0 && now - it.savedAt > PENDING_MAX_AGE_MS) }
            if (stale.isNotEmpty()) {
                list.removeAll(stale)
                writePending(list)
            }
            list.filter { it.sessionId != playing }
        }
        if (due.isEmpty()) {
            pendingInFlight.set(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val repo = PlaybackRepository(c)
                for (save in due) {
                    val r = repo.sync(save.sessionId, save.positionSec, save.listenedSec, save.durationSec)
                    when {
                        r is AbsorbHttpClient.Result.Ok -> {
                            repo.close(save.sessionId, save.positionSec)
                            dropPending(save.sessionId, exactly = save)
                            Log.i(TAG, "delivered the progress save kept from last time (${save.positionSec.toLong()}s)")
                        }
                        r is AbsorbHttpClient.Result.Failed && r.code == 404 -> {
                            dropPending(save.sessionId, exactly = save)
                            Log.i(TAG, "dropped a kept progress save the server no longer has a session for")
                        }
                        else -> Log.w(TAG, "kept progress save still not delivered: $r")
                    }
                }
            } finally {
                pendingInFlight.set(false)
            }
        }
    }

    private val pendingInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun keepPendingSync(c: Credentials, sessionId: String, pos: Double, listened: Double, duration: Double) {
        putPending(PendingSync(c.serverUrl, sessionId, pos, listened, duration, System.currentTimeMillis()))
        Log.w(TAG, "progress save kept for later: ${pos.toLong()}s")
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            pushState()
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                )
            ) {
                Log.i(
                    TAG,
                    "player state=${player.playbackState} playWhenReady=${player.playWhenReady} " +
                        "suppression=${player.playbackSuppressionReason} isPlaying=${player.isPlaying} " +
                        "item=${player.currentMediaItemIndex}/${player.mediaItemCount}",
                )
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) adoptMeasuredDurations(player)
            if (events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)) watchFocusLoss(player)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) &&
                !player.isPlaying && player.playbackState != Player.STATE_ENDED
            ) {
                // Pause/stall — flush progress immediately so we don't lose the
                // last stretch if the user walks away.
                scope.launch { syncNow() }
            }
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED
            ) {
                scope.launch { finishSession() }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason")
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            ) {
                lastPauseMs = System.currentTimeMillis()
            }
            if (playWhenReady) {
                // A resume soon after a sleep pause undoes its rewind first.
                SleepWatch.onLocalResume()
                applyResumeRewind()
            }
            // A focus request refused right after a start (the phone or the
            // earbuds still held it) leaves the book loaded but paused.
            // One retry a moment later usually gets it.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
                System.currentTimeMillis() - lastStartMs < 15_000 && !focusRetried
            ) {
                focusRetried = true
                scope.launch {
                    delay(1_500)
                    val p = _player.value ?: return@launch
                    if (!p.playWhenReady && p.mediaItemCount > 0) {
                        Log.i(TAG, "retrying play after an audio focus refusal")
                        p.play()
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error", error)
            _uiState.update {
                it.copy(isPlaying = false, isBuffering = false, error = errorMessage(error))
            }
            // A refused stream is nearly always a token that just rotated
            // or a network blip; one automatic restart from the same spot
            // saves a tap. Anything else stays for the user to retry.
            val ctx = appContext
            if (ctx != null && !errorRetried && error.errorCode in AUTO_RETRY_CODES) {
                errorRetried = true
                scope.launch {
                    delay(2_000)
                    Log.i(TAG, "restarting after ${error.errorCodeName}")
                    retry(ctx)
                }
            }
        }
    }

    // ---- lifecycle (called by AbsorbMediaService) ----

    fun attach(player: ExoPlayer, stopService: (() -> Unit)? = null) {
        _player.value = player
        stopServiceHook = stopService
        player.addListener(listener)
        player.setPlaybackSpeed(_uiState.value.speed)
        startTicker()
    }

    fun detach() {
        _player.value?.removeListener(listener)
        tickerJob?.cancel()
        syncJob?.cancel()
        playJob?.cancel()
        _player.value = null
        stopServiceHook = null
        // Service died (system reclaim / swipe-away): report what we know and
        // close the session so the server doesn't hold a phantom open player.
        flushAndCloseAsync()
        _selection.value = null
        _uiState.value = PlaybackUiState(speed = _uiState.value.speed)
    }

    // ---- UI entry points ----

    /** Stream [itemId] (or a podcast [episodeId]) on the watch, resuming from
     *  the server's saved position. [title]/[author] populate the now-playing
     *  surface and the media notification before metadata is otherwise known. */
    fun play(
        context: Context,
        creds: Credentials,
        itemId: String,
        episodeId: String?,
        title: String?,
        author: String?,
    ) = start(context, creds, itemId, episodeId, title, author, resumeAtSec = null, autoPlay = true)

    /** The user tapped a book: it is the selection, and when the watch is
     *  the target it loads here, paused, ready for the play button. With
     *  the phone as the target nothing happens until play. */
    fun select(
        context: Context,
        creds: Credentials,
        itemId: String,
        episodeId: String?,
        title: String?,
        author: String?,
    ) {
        this.creds = creds
        lastRequest = PlayArgs(itemId, episodeId, title, author)
        _selection.value = Selection(itemId, episodeId, title, author)
        if (_target.value == PlayTarget.WATCH) {
            load(context, creds, itemId, episodeId, title, author, resumeAtSec = null)
        }
    }

    /** Load a book on the watch without playing it, from [resumeAtSec]
     *  (a position taken over from the phone) or the server's spot. */
    fun load(
        context: Context,
        creds: Credentials,
        itemId: String,
        episodeId: String?,
        title: String?,
        author: String?,
        resumeAtSec: Double?,
    ) = start(context, creds, itemId, episodeId, title, author, resumeAtSec, autoPlay = false)

    private fun start(
        context: Context,
        creds: Credentials,
        itemId: String,
        episodeId: String?,
        title: String?,
        author: String?,
        resumeAtSec: Double?,
        autoPlay: Boolean,
    ) {
        this.creds = creds
        lastRequest = PlayArgs(itemId, episodeId, title, author)
        if (blockedByDownloadsOnly(itemId, episodeId)) {
            Log.i(TAG, "$itemId is not kept on the watch and downloads only is on; not loading")
            _selection.value = Selection(itemId, episodeId, title, author)
            return
        }
        playJob?.cancel()
        playJob = scope.launch {
            startPlayback(context, creds, itemId, episodeId, title, author, resumeAtSec, autoPlay)
        }
    }

    fun setTarget(target: PlayTarget) {
        _target.value = target
        prefs?.edit()?.putString(KEY_TARGET, target.name)?.apply()
    }

    /** The phone takes over: whatever the watch had loaded is flushed and
     *  let go, and the phone-remote screen shows the book from here on. */
    fun handOffToPhone(context: Context) {
        val s = _selection.value ?: return
        // The phone starts exactly here. The server can be ten minutes behind
        // (the watch saves to it that often, and the final save
        // below races the phone's start), and a book downloaded on the phone
        // would otherwise resume from the phone's own older spot. Only when
        // the watch has this very book loaded: picking another book for the
        // phone leaves the last one in the player.
        val loaded = _uiState.value
        val positionSec = _player.value
            ?.takeIf {
                it.mediaItemCount > 0 &&
                    it.currentMediaItem?.mediaId == s.itemId &&
                    loaded.itemId == s.itemId &&
                    loaded.episodeId == s.episodeId
            }
            ?.let { absolutePositionSec(it) }
            ?.takeIf { it > 0.0 }
        Log.i(TAG, "handing ${s.itemId} to the phone at ${positionSec?.let { "%.1f".format(it) } ?: "its own spot"}")
        com.barnabas.absorb.wear.player.PlayerStateStore.expectStart(s.itemId, s.title.takeIf { s.episodeId != null })
        // On the manager's own scope: the screen that asked is leaving.
        val commands = com.barnabas.absorb.wear.sync.PlayerCommandClient(context.applicationContext)
        scope.launch(Dispatchers.IO) {
            if (!commands.playItem(s.itemId, s.episodeId, positionSec)) {
                com.barnabas.absorb.wear.player.PlayerStateStore.cancelStart()
            }
        }
        stop()
    }

    /** Restart the last requested item after an error, resuming where the
     *  player got to. Re-opens the session from scratch — a dead stream URL
     *  (server restart, expired session) can't be fixed by re-preparing. */
    fun retry(context: Context) {
        val req = lastRequest ?: return
        val c = CredentialStore.credentials.value ?: creds ?: return
        val resumeAt = lastKnownPositionSec.takeIf { it > 0.0 }
        playJob?.cancel()
        playJob = scope.launch {
            startPlayback(context, c, req.itemId, req.episodeId, req.title, req.author, resumeAt)
        }
    }

    fun playPause() {
        val player = _player.value ?: return
        Log.i(TAG, "play/pause tapped in the app")
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            // Recovering from an error with the playlist intact — re-prepare
            // picks up from the current position.
            player.prepare()
        }
        player.playWhenReady = !player.playWhenReady
    }

    fun seekBack() = seekBy(-skipBackSec)

    fun seekForward() = seekBy(skipForwardSec)

    /** Seek relative to the absolute position, crossing track boundaries —
     *  ExoPlayer's own seekBack/seekForward clamp inside the current track,
     *  which strands skips at chapter-file edges in multi-file books. */
    private var lastChapterSnapMs = 0L

    fun seekBy(deltaSec: Int) {
        val player = _player.value ?: return
        val durationSec = _uiState.value.durationMs / 1000.0
        val pos = absolutePositionSec(player)
        // Scaled by the speed so a skip is the set amount of real time, as
        // on the phone.
        var target = pos + deltaSec * _uiState.value.speed
        if (deltaSec < 0 && settings.skipChapterBarrier) {
            val chapterStart = chapterAt(pos + 0.5)?.start ?: 0.0
            if (target < chapterStart && pos - chapterStart > 0.5) {
                val now = System.currentTimeMillis()
                if (now - lastChapterSnapMs > 2_000) {
                    // Stop at the chapter start; a second tap within two
                    // seconds goes on through, like the phone.
                    lastChapterSnapMs = now
                    target = chapterStart
                } else {
                    lastChapterSnapMs = 0L
                }
            }
        }
        if (durationSec > 0) target = target.coerceAtMost(durationSec)
        target = target.coerceAtLeast(0.0)
        val (index, offsetMs) = resolveSeek(target)
        player.seekTo(index, offsetMs)
        pushState()
    }

    /** Jump to an absolute position in the item, in seconds. */
    fun seekToSec(positionSec: Double) {
        val player = _player.value ?: return
        val durationSec = _uiState.value.durationMs / 1000.0
        var target = positionSec.coerceAtLeast(0.0)
        if (durationSec > 0) target = target.coerceAtMost(durationSec)
        val (index, offsetMs) = resolveSeek(target)
        player.seekTo(index, offsetMs)
        pushState()
    }

    /** The next chapter's start, or the end of the book from the last one. */
    fun nextChapter() {
        val player = _player.value ?: return
        val pos = absolutePositionSec(player)
        val next = chapters.firstOrNull { it.start > pos + 0.5 } ?: return
        seekToSec(next.start)
    }

    /** The current chapter's start, or the previous chapter's when already
     *  within three seconds of this one's, like the phone. */
    fun previousChapter() {
        val player = _player.value ?: return
        val pos = absolutePositionSec(player)
        val target = chapters.lastOrNull { it.start < pos - 3.0 }?.start ?: 0.0
        seekToSec(target)
    }

    fun cycleSpeed() {
        setSpeed(nextSpeed(_uiState.value.speed, settings.speedPresets))
    }

    fun setSpeed(speed: Float) {
        prefs?.edit()?.putFloat(KEY_SPEED, speed)?.apply()
        _player.value?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(speed = speed) }
    }

    /** Stop playback and end the ABS session. Used by sign-out. */
    fun stop() {
        val player = _player.value
        if (player != null) {
            lastKnownPositionSec = absolutePositionSec(player)
            player.stop()
            player.clearMediaItems()
        }
        syncJob?.cancel()
        playJob?.cancel()
        flushAndCloseAsync()
        _selection.value = null
        _uiState.value = PlaybackUiState(speed = _uiState.value.speed)
        stopServiceHook?.invoke()
    }

    // ---- internals ----

    private suspend fun startPlayback(
        context: Context,
        creds: Credentials,
        itemId: String,
        episodeId: String?,
        title: String?,
        author: String?,
        resumeAtSec: Double?,
        autoPlay: Boolean = true,
    ) {
        lastStartMs = System.currentTimeMillis()
        focusRetried = false
        if (resumeAtSec == null) errorRetried = false
        // The watch playing its own book takes over the media controls.
        com.barnabas.absorb.wear.player.PhoneMirrorSession.release()
        retryPendingSync()
        AbsorbMediaService.start(context)
        val player = _player.filterNotNull().first()

        // Tapping the already-loaded item again just resumes it.
        val current = _uiState.value
        if (sessionId != null && current.itemId == itemId && current.episodeId == episodeId &&
            player.mediaItemCount > 0 && current.error == null
        ) {
            if (autoPlay) player.playWhenReady = true
            return
        }

        // The book being switched away from gets its last spot and listening
        // time sent, the same as a stop: closing alone saves nothing on an
        // Audiobookshelf session, and a local or narration session has no
        // close at all.
        if (sessionId != null) {
            if (player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0) {
                lastKnownPositionSec = absolutePositionSec(player)
            }
            flushAndCloseAsync()
        }
        this.creds = creds

        _selection.value = Selection(itemId, episodeId, title, author)
        _uiState.value = PlaybackUiState(
            itemId = itemId,
            episodeId = episodeId,
            title = title,
            author = author,
            isBuffering = true,
            speed = _uiState.value.speed,
        )

        Log.i(TAG, "starting item=$itemId episode=$episodeId backend=${creds.backend} on ${creds.serverUrl}")
        val session = when (val r = PlaybackRepository(creds).start(itemId, episodeId, deviceInfo(context))) {
            is AbsorbHttpClient.Result.Ok -> r.value
            is AbsorbHttpClient.Result.Unauthorized -> {
                Log.w(TAG, "session start rejected: HTTP ${r.code}")
                _uiState.update { it.copy(isBuffering = false, error = "Session rejected — sign in again") }
                return
            }
            is AbsorbHttpClient.Result.Failed -> {
                Log.w(TAG, "session start failed: ${r.message} (HTTP ${r.code})")
                _uiState.update { it.copy(isBuffering = false, error = r.message) }
                return
            }
        }
        if (session.audioTracks.isEmpty()) {
            _uiState.update { it.copy(isBuffering = false, error = "No audio tracks") }
            return
        }

        sessionId = session.id
        chapters = session.chapters.sortedBy { it.start }
        _uiState.update { it.copy(chapters = chapters) }

        val tracks = session.audioTracks.sortedBy { it.index }
        Log.i(
            TAG,
            "session ${session.id}: ${tracks.size} track(s), duration=${session.duration}s, " +
                "resume=${resumeAtSec ?: session.currentTime}s, first=${tracks.first().contentUrl.take(80)}",
        )
        trackStartsSec = DoubleArray(tracks.size)
        trackDurationsSec = DoubleArray(tracks.size) { tracks[it].duration }
        var acc = 0.0
        // The media controls take their artwork from this one address and
        // never retry, so a cover kept on the watch wins over the server,
        // and any book played gets its cover kept for next time.
        val keptCover = com.barnabas.absorb.wear.download.CoverStore.existing(itemId)
        val artworkUri = keptCover?.let { android.net.Uri.fromFile(it) } ?: StreamUrl.forCover(creds, itemId).toUri()
        if (keptCover == null) {
            scope.launch(Dispatchers.IO) { com.barnabas.absorb.wear.download.CoverStore.save(creds, itemId) }
        }
        val items = tracks.mapIndexed { i, track ->
            trackStartsSec[i] = if (track.startOffset > 0.0) track.startOffset else acc
            acc += track.duration
            MediaItem.Builder()
                .setMediaId(itemId)
                .setUri(StreamUrl.forTrack(creds, track.contentUrl))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(author)
                        .setArtworkUri(artworkUri)
                        .build(),
                )
                .build()
        }

        val durationSec = if (session.duration > 0.0) session.duration else acc
        var resumePos = (resumeAtSec ?: session.currentTime).coerceIn(0.0, durationSec)
        if (resumeAtSec == null) resumePos = sessionStartRewind(resumePos)
        lastPauseMs = null
        val (index, offsetMs) = resolveSeek(resumePos)
        lastKnownPositionSec = resumePos
        listenedSinceSyncSec = 0.0
        player.setMediaItems(items, index, offsetMs)
        player.setPlaybackSpeed(_uiState.value.speed)
        // A streamed book needs the Wi-Fi radio held open or it stalls once
        // the screen sleeps; a book played from the watch's own copy only
        // needs the CPU, so the radio is left to doze and the battery lasts.
        val fromCopy = com.barnabas.absorb.wear.download.BookDownloads.isDownloaded(
            com.barnabas.absorb.wear.download.keepKey(itemId, episodeId),
        )
        player.setWakeMode(if (fromCopy) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        player.playWhenReady = autoPlay
        player.prepare()

        _uiState.update {
            it.copy(
                isBuffering = true,
                durationMs = (durationSec * 1000).toLong(),
                error = null,
            )
        }
        startSyncLoop()
    }

    private var focusWatchJob: Job? = null

    /**
     * Another app (the watch's Settings, a system sound) can take audio
     * focus for a moment and never give it back, which leaves the player
     * silently waiting with play still pressed. After ten seconds of that,
     * ask for focus again by restarting play; a real loss (a call) refuses
     * and nothing changes.
     */
    private fun watchFocusLoss(player: Player) {
        focusWatchJob?.cancel()
        if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) return
        focusWatchJob = scope.launch {
            delay(10_000)
            val p = _player.value ?: return@launch
            if (p.playWhenReady &&
                p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            ) {
                Log.i(TAG, "audio focus never came back; asking again")
                p.pause()
                p.play()
            }
        }
    }

    /**
     * Lay the tracks out on the lengths ExoPlayer measured once a file's
     * header is read. The server's lengths can be approximate (a narrated
     * EPUB's come from the last sentence's end, not the file), and a wrong
     * length throws off every position past that file. A file not read yet
     * keeps the declared length.
     */
    private fun adoptMeasuredDurations(player: Player) {
        val timeline = player.currentTimeline
        val n = trackStartsSec.size
        if (n == 0 || timeline.windowCount != n) return
        val window = androidx.media3.common.Timeline.Window()
        val starts = DoubleArray(n)
        var acc = 0.0
        var changed = false
        for (i in 0 until n) {
            starts[i] = acc
            timeline.getWindow(i, window)
            val measured = window.durationMs
            val declared = trackDurationsSec.getOrElse(i) { 0.0 }
            val d = if (measured != androidx.media3.common.C.TIME_UNSET) measured / 1000.0 else declared
            if (abs(d - declared) > 0.5) changed = true
            acc += d
        }
        if (!changed) return
        trackStartsSec = starts
        trackDurationsSec = DoubleArray(n) { i -> (if (i + 1 < n) starts[i + 1] else acc) - starts[i] }
        _uiState.update { it.copy(durationMs = (acc * 1000).toLong()) }
        Log.i(TAG, "track lengths adopted from the files: total=${acc.toLong()}s")
    }

    /** Map an absolute position (seconds) to a (trackIndex, offsetMs) pair. */
    private fun resolveSeek(positionSec: Double): Pair<Int, Long> {
        if (trackStartsSec.isEmpty()) return 0 to 0L
        var index = 0
        for (i in trackStartsSec.indices) {
            if (positionSec >= trackStartsSec[i]) index = i else break
        }
        val offsetSec = (positionSec - trackStartsSec[index]).coerceAtLeast(0.0)
        return index to (offsetSec * 1000).toLong()
    }

    private fun absolutePositionSec(player: Player): Double {
        val index = player.currentMediaItemIndex
        val base = trackStartsSec.getOrElse(index) { 0.0 }
        return base + player.currentPosition / 1000.0
    }

    private fun chapterAt(positionSec: Double): Chapter? = chapters.lastOrNull { positionSec >= it.start }

    private var lastPauseMs: Long? = null

    /** The phone's auto rewind on resume: a little after a short pause,
     *  up to the most after an hour, scaled by the speed and held at the
     *  chapter start when the setting says so. */
    private fun applyResumeRewind() {
        val pausedAt = lastPauseMs ?: return
        lastPauseMs = null
        val s = settings
        if (!s.autoRewindEnabled) return
        val player = _player.value ?: return
        val pauseSec = (System.currentTimeMillis() - pausedAt) / 1000.0
        val rewind = autoRewindSeconds(pauseSec, s)
        val pos = absolutePositionSec(player)
        if (rewind <= 0.5 || pos <= 0.0) return
        var target = (pos - rewind * _uiState.value.speed).coerceAtLeast(0.0)
        if (s.autoRewindChapterBarrier) chapterAt(pos)?.let { if (target < it.start) target = it.start }
        if (pos - target <= 0.0) return
        val (index, offsetMs) = resolveSeek(target)
        player.seekTo(index, offsetMs)
        Log.i(TAG, "auto rewind ${"%.1f".format(rewind)}s after a ${pauseSec.toLong()}s pause")
    }

    private fun autoRewindSeconds(pauseSec: Double, s: Settings): Double {
        if (pauseSec < s.autoRewindDelaySec) return 0.0
        val effective = (pauseSec - s.autoRewindDelaySec).coerceIn(0.0, 3600.0)
        val t = effective / 3600.0
        return (s.autoRewindMinSec + (s.autoRewindMaxSec - s.autoRewindMinSec) * t)
            .coerceIn(minOf(s.autoRewindMinSec, s.autoRewindMaxSec), maxOf(s.autoRewindMinSec, s.autoRewindMaxSec))
    }

    /** The phone's rewind at the start of a session, from the server's spot. */
    private fun sessionStartRewind(start: Double): Double {
        val s = settings
        if (!s.autoRewindEnabled || !s.autoRewindSessionStart || start <= 0.0) return start
        val rewind = s.autoRewindMaxSec
        if (rewind <= 0.5) return start
        var target = (start - rewind * _uiState.value.speed).coerceAtLeast(0.0)
        if (s.autoRewindChapterBarrier) chapterAt(start)?.let { if (target < it.start) target = it.start }
        if (start - target <= 0.0) return start
        Log.i(TAG, "session start rewind ${"%.1f".format(start - target)}s")
        return target
    }

    private fun chapterTitleAt(positionSec: Double): String? = chapterAt(positionSec)?.title?.takeIf { it.isNotBlank() }

    private fun pushState() {
        val player = _player.value ?: return
        if (!_uiState.value.hasItem) return
        val posSec = absolutePositionSec(player)
        if (player.playbackState != Player.STATE_IDLE) lastKnownPositionSec = posSec
        _uiState.update {
            it.copy(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                isReady = player.playbackState == Player.STATE_READY,
                needsAudioOutput = player.playbackSuppressionReason ==
                    Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
                positionMs = (posSec * 1000).toLong(),
                chapterTitle = chapterTitleAt(posSec),
                chapterStartMs = chapterAt(posSec)?.let { (it.start * 1000).toLong() } ?: 0L,
                chapterEndMs = chapterAt(posSec)?.let { (it.end * 1000).toLong() } ?: 0L,
                skipBackSec = skipBackSec,
                skipForwardSec = skipForwardSec,
                // A player that made it back to a live state has recovered
                // from whatever the error was.
                error = if (player.playbackState == Player.STATE_READY ||
                    player.playbackState == Player.STATE_BUFFERING
                ) null else it.error,
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var ticks = 0
            while (true) {
                if (_player.value?.isPlaying == true) {
                    listenedSinceSyncSec += 1.0
                    if (++ticks % LOCAL_SAVE_EVERY_TICKS == 0) persistLocalProgress()
                }
                pushState()
                delay(1_000)
            }
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                if (_player.value?.isPlaying == true) syncNow()
            }
        }
    }

    private suspend fun syncNow() {
        val player = _player.value ?: return
        val sid = sessionId ?: return
        val c = CredentialStore.credentials.value ?: creds ?: return
        val posSec = absolutePositionSec(player)
        val listened = listenedSinceSyncSec
        listenedSinceSyncSec = 0.0
        val durationSec = _uiState.value.durationMs / 1000.0
        // The save on disk now holds what this sync sends. One written while
        // it is in flight only holds time listened since, and stays.
        val onDisk = synchronized(pendingLock) { pendingList().firstOrNull { it.sessionId == sid } }
        val result = withContext(Dispatchers.IO) {
            PlaybackRepository(c).sync(sid, posSec, listened, durationSec)
        }
        if (result is AbsorbHttpClient.Result.Ok) {
            if (onDisk != null) dropPending(sid, exactly = onDisk)
        } else {
            // Re-credit the listened time so it ships with the next attempt,
            // and keep the spot on disk in case there is no next attempt.
            listenedSinceSyncSec += listened
            keepPendingSync(c, sid, posSec, listenedSinceSyncSec, durationSec)
            Log.w(TAG, "Progress sync failed: $result")
        }
    }

    /** The current spot and unsent listening time, written to disk so a
     *  process killed mid-book delivers them on its next start. */
    private fun persistLocalProgress() {
        val player = _player.value ?: return
        val sid = sessionId ?: return
        val c = CredentialStore.credentials.value ?: creds ?: return
        val pos = absolutePositionSec(player)
        _uiState.value.itemId?.let {
            com.barnabas.absorb.wear.download.DownloadStore.setLastPosition(
                com.barnabas.absorb.wear.download.keepKey(it, _uiState.value.episodeId),
                pos,
            )
        }
        putPending(
            PendingSync(
                c.serverUrl,
                sid,
                pos,
                listenedSinceSyncSec,
                _uiState.value.durationMs / 1000.0,
                System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun finishSession() {
        val sid = sessionId ?: return
        val c = CredentialStore.credentials.value ?: creds ?: return
        sessionId = null
        syncJob?.cancel()
        val durationSec = _uiState.value.durationMs / 1000.0
        val listened = listenedSinceSyncSec
        listenedSinceSyncSec = 0.0
        // This final save replaces the one on disk; it goes back only if
        // the final save fails.
        dropPending(sid)
        withContext(Dispatchers.IO + NonCancellable) {
            val repo = PlaybackRepository(c)
            // Position = duration marks the book finished on the server.
            val r = repo.sync(sid, durationSec, listened, durationSec)
            if (r is AbsorbHttpClient.Result.Ok) {
                repo.close(sid)
            } else {
                keepPendingSync(c, sid, durationSec, listened, durationSec)
            }
        }
        _player.value?.clearMediaItems()
        _selection.value = null
        _uiState.value = PlaybackUiState(speed = _uiState.value.speed)
        stopServiceHook?.invoke()
    }

    /** Best-effort final sync + close with whatever we last knew. Runs
     *  NonCancellable because it's called from teardown paths. */
    private fun flushAndCloseAsync() {
        val sid = sessionId ?: return
        val c = CredentialStore.credentials.value ?: creds ?: return
        sessionId = null
        val pos = lastKnownPositionSec
        val listened = listenedSinceSyncSec
        listenedSinceSyncSec = 0.0
        val durationSec = _uiState.value.durationMs / 1000.0
        // This final save replaces the one on disk (a retry must not send
        // that too); it goes back only if the final save fails.
        dropPending(sid)
        scope.launch(Dispatchers.IO + NonCancellable) {
            val repo = PlaybackRepository(c)
            val r = repo.sync(sid, pos, listened, durationSec)
            if (r is AbsorbHttpClient.Result.Ok) {
                repo.close(sid, pos)
            } else {
                // No network right now (the watch was between Wi-Fi and the
                // phone's Bluetooth link): keep it for the next chance.
                keepPendingSync(c, sid, pos, listened, durationSec)
            }
        }
    }

    private fun errorMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Network hiccup — can't reach server"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server refused the stream"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> "No audio output — connect headphones"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        -> "This audio format can't play on the watch"
        else -> "Playback error (${error.errorCodeName})"
    }

    private fun deviceInfo(context: Context): PlaybackRepository.DeviceInfo = PlaybackRepository.DeviceInfo.of(context)
}
