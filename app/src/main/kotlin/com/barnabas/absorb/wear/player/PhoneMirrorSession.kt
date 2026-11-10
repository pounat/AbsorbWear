package com.barnabas.absorb.wear.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaSession
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.download.CoverStore
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The phone's playback as a media session on the watch, the way Spotify
 * mirrors the phone: the watch face complication and the system media
 * controls show our book and artwork, transport taps go to the phone over
 * the Data Layer, and opening the session lands on the phone-remote
 * screen. The position ticks between the phone's pushes from the last push
 * and the speed. Lives only while the phone has a book loaded and the
 * watch is not playing one itself.
 */
@UnstableApi
object PhoneMirrorSession {

    private const val TAG = "AbsorbWearPlayer"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var player: MirrorPlayer? = null
    private var session: MediaSession? = null
    private var artworkFor: String? = null
    private var artwork: ByteArray? = null

    /** The session, for a media-style notification. */
    val current: MediaSession? get() = session

    fun update(context: Context, state: PlayerState) {
        main.post { updateOnMain(context.applicationContext, state) }
    }

    fun release() {
        main.post { releaseOnMain() }
    }

    private fun updateOnMain(context: Context, state: PlayerState) {
        if (!state.hasBook) {
            releaseOnMain()
            return
        }
        val p = player ?: MirrorPlayer(context).also { player = it }
        if (session == null) {
            val openApp = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_REMOTE_MEDIA_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            session = MediaSession.Builder(context, p)
                .setId("absorb-phone")
                .setSessionActivity(openApp)
                .setCallback(object : MediaSession.Callback {
                    // An earbud's double or triple press arrives as next or
                    // previous; the phone treats those as its skips, so the
                    // watch asks it for the same rather than letting the
                    // mirror seek its one-item playlist.
                    override fun onMediaButtonEvent(
                        session: MediaSession,
                        controllerInfo: MediaSession.ControllerInfo,
                        intent: Intent,
                    ): Boolean {
                        val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        when (key?.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Unit
                            else -> return super.onMediaButtonEvent(session, controllerInfo, intent)
                        }
                        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                            if (key.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) p.skipForward() else p.skipBack()
                        }
                        return true
                    }
                })
                .build()
            Log.i(TAG, "phone mirror session created")
        }
        val itemId = state.itemId
        if (itemId != null && itemId != artworkFor) {
            artworkFor = itemId
            artwork = CoverStore.existing(itemId)?.readBytes()
            if (artwork == null) {
                val creds = CredentialStore.credentials.value
                if (creds != null) {
                    scope.launch {
                        CoverStore.save(creds, itemId)
                        val bytes = CoverStore.existing(itemId)?.readBytes() ?: return@launch
                        main.post {
                            if (artworkFor == itemId) {
                                artwork = bytes
                                player?.apply(state = player?.last ?: state, artwork = bytes)
                            }
                        }
                    }
                }
            }
        }
        p.apply(state, artwork)
    }

    private fun releaseOnMain() {
        session?.release()
        session = null
        player?.release()
        player = null
        artworkFor = null
        artwork = null
    }

    /**
     * A player with no audio of its own: its state is whatever the phone
     * last said, and its commands are messages to the phone.
     */
    private class MirrorPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

        private val commands = PlayerCommandClient(context)
        var last: PlayerState? = null
            private set
        private var artworkData: ByteArray? = null

        private val available = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_DEVICE_VOLUME,
            )
            .build()

        private var pushes = 0
        private var bumping = false

        fun apply(state: PlayerState, artwork: ByteArray?) {
            last = state
            artworkData = artwork
            // The system ranks sessions by the last time one went from
            // not-playing to playing, and opening its own controls re-ranks
            // its proxy above us. Reporting a momentary pause and then
            // playing again every few pushes keeps the complication on us.
            if (state.isPlaying && ++pushes % 3 == 0) {
                bumping = true
                invalidateState()
                bumping = false
            }
            invalidateState()
        }

        override fun getState(): State {
            val s = last ?: return State.Builder().setAvailableCommands(available).build()
            val metadata = MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.author)
                .setSubtitle(s.chapter)
                .setDurationMs(s.durationMs.takeIf { it > 0 })
                .apply { artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
            val item = MediaItem.Builder()
                .setMediaId(s.itemId ?: "phone")
                .setMediaMetadata(metadata)
                .build()
            val position = if (s.isPlaying) {
                PositionSupplier.getExtrapolating(
                    s.positionMs + ((System.currentTimeMillis() - s.issuedAt).coerceAtLeast(0) * s.speed).toLong(),
                    s.speed,
                )
            } else {
                PositionSupplier.getConstant(s.positionMs)
            }
            return State.Builder()
                .setAvailableCommands(available)
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(s.isPlaying && !bumping, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setPlaylist(
                    listOf(
                        MediaItemData.Builder(s.itemId ?: "phone")
                            .setMediaItem(item)
                            .setMediaMetadata(metadata)
                            .setDurationUs(if (s.durationMs > 0) s.durationMs * 1000 else androidx.media3.common.C.TIME_UNSET)
                            .build(),
                    ),
                )
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(position)
                .setPlaybackParameters(PlaybackParameters(s.speed))
                .setSeekBackIncrementMs(s.skipBackSec * 1000L)
                .setSeekForwardIncrementMs(s.skipForwardSec * 1000L)
                .build()
        }

        fun skipForward() {
            scope.launch { commands.skipForward() }
        }

        fun skipBack() {
            scope.launch { commands.skipBack() }
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            val s = last
            if (s != null && s.isPlaying != playWhenReady) {
                // Show the tap at once; the phone's next push confirms it.
                last = s.copy(isPlaying = playWhenReady, issuedAt = System.currentTimeMillis())
                scope.launch { commands.playPause() }
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_BACK -> scope.launch { commands.skipBack() }
                Player.COMMAND_SEEK_FORWARD -> scope.launch { commands.skipForward() }
                else -> Unit
            }
            return Futures.immediateVoidFuture()
        }
    }
}
