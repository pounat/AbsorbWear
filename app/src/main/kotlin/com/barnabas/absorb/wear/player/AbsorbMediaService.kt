package com.barnabas.absorb.wear.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.WearUnsuitableOutputPlaybackSuppressionResolverListener
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.playback.PlaybackManager

/**
 * Foreground media service that streams ABS audio on the watch.
 *
 * Owns the ExoPlayer (so playback survives screen-off and the system shows a
 * media notification + ongoing activity for free) and hands it to
 * [PlaybackManager], which drives the session lifecycle. Auth for an ABS
 * stream rides in the URL (`?token=`); a BookOrbit stream is signed per
 * request by [BearerDataSource]. Reverse-proxy [customHeaders] are attached
 * to the HTTP data source here.
 */
@UnstableApi
class AbsorbMediaService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        CredentialStore.credentials.value?.customHeaders
            ?.takeIf { it.isNotEmpty() }
            ?.let { httpFactory.setDefaultRequestProperties(it) }
        // Network sources sign BookOrbit requests and go through the on-disk
        // cache, so a replay or a seek back does not buffer from the server
        // again; narrated-EPUB tracks on Audiobookshelf are pulled from the
        // EPUB into their own files and played from there.
        val network = DefaultDataSource.Factory(this, BearerDataSource.Factory(httpFactory))
        val streamed = CacheDataSource.Factory()
            .setCache(MediaCache.streams(this))
            .setUpstreamDataSourceFactory(network)
            .setCacheKeyFactory { spec -> MediaCache.cacheKey(spec.uri) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Books kept on the watch are read first and never written to by
        // playback, so streaming can never crowd them out.
        val kept = CacheDataSource.Factory()
            .setCache(MediaCache.downloads(this))
            .setUpstreamDataSourceFactory(streamed)
            .setCacheKeyFactory { spec -> MediaCache.cacheKey(spec.uri) }
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = NarrationTrackDataSource.Factory(kept)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Hold wifi + a partial wake lock while streaming: without this
            // the radio idles when the screen sleeps and playback stalls a
            // few minutes into a book. PlaybackManager drops it to
            // WAKE_MODE_LOCAL for a book played from the watch's copy.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(PlaybackManager.skipBackSec * 1000L)
            .setSeekForwardIncrementMs(PlaybackManager.skipForwardSec * 1000L)
            // Wear OS does not play media through the watch speaker. Without
            // this, a play with no headphones connected "plays" into nothing;
            // with it, ExoPlayer reports PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_
            // AUDIO_OUTPUT, the listener below pauses and opens the system
            // output picker, and playback resumes by itself once a Bluetooth
            // device connects (within five minutes).
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build()
        exo.addListener(WearUnsuitableOutputPlaybackSuppressionResolverListener(this))

        player = exo
        PlaybackManager.attach(exo) { pauseAllPlayersAndStopSelf() }

        // Seeks issued by the system surface (media controls / BT buttons)
        // must cross track boundaries like the in-app buttons do, so route
        // them through PlaybackManager's absolute seek. Previous and next
        // (an earbud's double or triple press) are the same skips, as on
        // the phone: ExoPlayer's own would jump to the start of the track,
        // which for a one-file book means the start of the book.
        // Transport from outside the app (system media controls, earbuds,
        // Bluetooth buttons) comes through here, so it is logged as such:
        // a pause from a controller looks the same to the player as our own.
        val sessionPlayer = object : ForwardingPlayer(exo) {
            override fun seekBack() = PlaybackManager.seekBack()
            override fun seekForward() = PlaybackManager.seekForward()
            override fun seekToPrevious() = PlaybackManager.seekBack()
            override fun seekToNext() = PlaybackManager.seekForward()
            override fun seekToPreviousMediaItem() = PlaybackManager.seekBack()
            override fun seekToNextMediaItem() = PlaybackManager.seekForward()
            // ExoPlayer only offers next and previous when another media
            // item exists, so with a one-file book the session would drop
            // the key before the overrides above ran.
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun pause() {
                android.util.Log.i("AbsorbWearPlayback", "pause from a session controller")
                super.pause()
            }
            override fun play() {
                android.util.Log.i("AbsorbWearPlayback", "play from a session controller")
                super.play()
            }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                android.util.Log.i("AbsorbWearPlayback", "playWhenReady=$playWhenReady from a session controller")
                super.setPlayWhenReady(playWhenReady)
            }
        }

        // Tapping the media control / ongoing activity opens our now-playing UI.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val built = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(openApp)
            // Artwork for the media notification is fetched by Media3's own
            // loader, not Coil — give it the same data source so reverse-proxy
            // headers ride along and the cover doesn't 403.
            .setBitmapLoader(
                CacheBitmapLoader(
                    DataSourceBitmapLoader(
                        DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
                        dataSourceFactory,
                    ),
                ),
            )
            .build()
        session = built
        // The service only posts the media notification and goes foreground
        // for sessions it has been given. Normally a connecting controller
        // triggers that through onGetSession; here the app drives the player
        // directly and nothing connects, so without this the service stayed a
        // plain background service and the system paused it in a pocket.
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p: Player? = session?.player
        if (p == null || (!p.playWhenReady && p.mediaItemCount == 0)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackManager.detach()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context.applicationContext, AbsorbMediaService::class.java))
        }
    }
}
