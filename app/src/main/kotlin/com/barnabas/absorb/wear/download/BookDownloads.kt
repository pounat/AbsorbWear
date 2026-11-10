package com.barnabas.absorb.wear.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.PodcastEpisodes
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.AbsNarration
import com.barnabas.absorb.wear.playback.AbsNarrationPlayback
import com.barnabas.absorb.wear.playback.AudioTrack
import com.barnabas.absorb.wear.playback.BookOrbitPlayback
import com.barnabas.absorb.wear.playback.Chapter
import com.barnabas.absorb.wear.playback.PlaybackSession
import com.barnabas.absorb.wear.playback.StreamUrl
import com.barnabas.absorb.wear.player.BearerDataSource
import com.barnabas.absorb.wear.player.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps whole books on the watch.
 *
 * A book's tracks are resolved to addresses that stay valid (BookOrbit
 * assets and narration files, Audiobookshelf's own audio files rather than
 * a play session's short-lived URLs) and handed to Media3's download
 * manager, which fills the same cache the player streams through. A
 * narrated EPUB on Audiobookshelf, or one on BookOrbit whose audio BookOrbit
 * can't serve, is pulled file by file into the narration cache instead. Progress per book is published for the UI, and
 * [DownloadStore] remembers what is kept and how to start it offline.
 */
@UnstableApi
object BookDownloads {

    private const val TAG = "AbsorbWearDownload"

    /** How far along a book is: 0..1, complete, or failed. */
    data class Progress(val fraction: Float, val complete: Boolean, val failed: Boolean = false) {
        val percent: Int get() = (fraction * 100).toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadManager: DownloadManager? = null
    private lateinit var appContext: Context

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    /** Call once from Application.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
        DownloadStore.init(appContext)
        manager(appContext)
        scope.launch { refreshAll() }
    }

    @Synchronized
    fun manager(context: Context): DownloadManager {
        downloadManager?.let { return it }
        val ctx = context.applicationContext
        appContext = ctx
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        CredentialStore.credentials.value?.customHeaders
            ?.takeIf { it.isNotEmpty() }
            ?.let { httpFactory.setDefaultRequestProperties(it) }
        val upstream = DefaultDataSource.Factory(ctx, BearerDataSource.Factory(httpFactory))
        val m = DownloadManager(
            ctx,
            StandaloneDatabaseProvider(ctx),
            MediaCache.downloads(ctx),
            upstream,
            Executors.newFixedThreadPool(2),
        )
        m.maxParallelDownloads = 1
        m.requirements = Requirements(Requirements.NETWORK)
        m.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                if (finalException != null) Log.w(TAG, "${download.request.id} failed", finalException)
                refresh(keyOf(download.request.id))
                if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED) pollWhileActive()
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                refresh(keyOf(download.request.id))
            }
        })
        downloadManager = m
        return m
    }

    /** Whether the book filed under [key] (see [keepKey]) is all here. */
    fun isDownloaded(key: String): Boolean = _progress.value[key]?.complete == true

    private var pollJob: kotlinx.coroutines.Job? = null

    /** The manager only reports state changes, not bytes, so while anything
     *  is downloading the percentages are re-read once a second. */
    private fun pollWhileActive() {
        if (pollJob?.isActive == true) return
        pollJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val m = downloadManager ?: break
                val active = m.currentDownloads.filter {
                    it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
                }
                if (active.isEmpty()) break
                active.map { keyOf(it.request.id) }.distinct().forEach { refresh(it) }
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    /** The [keepKey] a Media3 download id (`<key>#<track>`) belongs to. */
    fun keyOf(downloadId: String): String = downloadId.substringBeforeLast('#')

    /** Start keeping [item] (a book, or one podcast episode) on the watch.
     *  Returns null on success, else why not. */
    suspend fun start(creds: Credentials, item: InProgressItem): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val itemId = item.id
        val episodeId = item.episodeId
        val key = keepKey(itemId, episodeId)
        if (DownloadStore[key] != null) return@withContext null
        val client = AbsorbHttpClient(creds)
        val resolved = try {
            if (episodeId != null) resolveEpisode(creds, itemId, episodeId) else resolve(client, creds, itemId)
        } catch (e: Exception) {
            Log.w(TAG, "could not resolve $key for download", e)
            return@withContext e.message ?: "Could not read the book"
        }
        val (session, kind) = resolved
            ?: return@withContext if (episodeId != null) "This episode has no audio" else "This book has no audio"
        val ids = if (kind == DownloadedBook.KIND_URL) session.audioTracks.mapIndexed { i, _ -> "$key#$i" } else emptyList()
        val book = DownloadedBook(
            itemId = itemId,
            serverUrl = creds.serverUrl,
            backend = creds.backend,
            title = item.displayTitle,
            author = item.displaySubtitle,
            kind = kind,
            session = session,
            downloadIds = ids,
            addedAtMs = System.currentTimeMillis(),
            episodeId = episodeId,
        )
        DownloadStore.put(book)
        _progress.update { it + (key to Progress(0f, complete = false)) }
        scope.launch { CoverStore.save(creds, itemId) }
        if (kind == DownloadedBook.KIND_URL) {
            session.audioTracks.forEachIndexed { i, track ->
                val url = StreamUrl.forTrack(creds, track.contentUrl)
                val request = DownloadRequest.Builder("$key#$i", Uri.parse(url))
                    .setCustomCacheKey(MediaCache.cacheKey(Uri.parse(url)))
                    .build()
                DownloadService.sendAddDownload(appContext, AbsorbDownloadService::class.java, request, false)
            }
            pollWhileActive()
        } else {
            NarrationPullService.start(appContext, itemId, session.audioTracks.size)
        }
        Log.i(TAG, "keeping $key: ${session.audioTracks.size} track(s), kind=$kind")
        null
    }

    /** Stop keeping the book filed under [key] (see [keepKey]). */
    fun remove(key: String) {
        val book = DownloadStore[key] ?: return
        book.downloadIds.forEach {
            DownloadService.sendRemoveDownload(appContext, AbsorbDownloadService::class.java, it, false)
        }
        if (book.isNarration) scope.launch { AbsNarration.deleteCached(book.itemId) }
        DownloadStore.remove(key)
        // A podcast's cover stays while another of its episodes is kept.
        if (DownloadStore.books.value.values.none { it.itemId == book.itemId }) CoverStore.delete(book.itemId)
        _progress.update { it - key }
    }

    /** One podcast episode's audio file, by its stable address. */
    private suspend fun resolveEpisode(creds: Credentials, itemId: String, episodeId: String): Pair<PlaybackSession, String>? {
        val episodes = when (val r = PodcastEpisodes.load(creds, itemId)) {
            is AbsorbHttpClient.Result.Ok -> r.value
            is AbsorbHttpClient.Result.Unauthorized -> throw IllegalStateException("Signed out")
            is AbsorbHttpClient.Result.Failed -> throw IllegalStateException(r.message)
        }
        val episode = episodes.firstOrNull { it.id == episodeId } ?: return null
        val file = episode.audioFile ?: return null
        val ino = file.ino ?: return null
        val length = episode.lengthSec
        val track = AudioTrack(
            index = 1,
            contentUrl = "/api/items/$itemId/file/$ino",
            duration = length,
            startOffset = 0.0,
            mimeType = file.mimeType,
        )
        val session = PlaybackSession(
            id = "kept",
            libraryItemId = itemId,
            audioTracks = listOf(track),
            chapters = episode.chapters,
            duration = length,
            currentTime = 0.0,
        )
        return session to DownloadedBook.KIND_URL
    }

    /** The tracks, chapters and length that stay valid after today. */
    private suspend fun resolve(client: AbsorbHttpClient, creds: Credentials, itemId: String): Pair<PlaybackSession, String>? {
        if (creds.isBookOrbit) {
            val r = BookOrbitPlayback(client, creds).start(itemId)
            val session = (r as? AbsorbHttpClient.Result.Ok)?.value ?: return null
            // Audio BookOrbit can't serve is pulled out of the EPUB instead.
            val fromEpub = session.audioTracks.any { it.contentUrl.startsWith("${AbsNarration.SCHEME}://") }
            return session.copy(id = "kept") to if (fromEpub) DownloadedBook.KIND_NARRATION else DownloadedBook.KIND_URL
        }
        val detail = when (val r = client.get("/api/items/$itemId", AbsItem.serializer())) {
            is AbsorbHttpClient.Result.Ok -> r.value
            is AbsorbHttpClient.Result.Unauthorized -> throw IllegalStateException("Signed out")
            is AbsorbHttpClient.Result.Failed -> throw IllegalStateException(r.message)
        }
        val media = detail.media
        val files = media?.audioFiles.orEmpty().sortedBy { it.index }
        if (files.isNotEmpty()) {
            var acc = 0.0
            val tracks = files.mapIndexed { i, f ->
                AudioTrack(
                    index = i + 1,
                    contentUrl = "/api/items/$itemId/file/${f.ino}",
                    duration = f.duration,
                    startOffset = acc,
                    mimeType = f.mimeType,
                ).also { acc += f.duration }
            }
            val session = PlaybackSession(
                id = "kept",
                libraryItemId = itemId,
                audioTracks = tracks,
                chapters = media?.chapters.orEmpty(),
                duration = media?.duration?.takeIf { it > 0 } ?: acc,
                currentTime = 0.0,
            )
            return session to DownloadedBook.KIND_URL
        }
        val r = AbsNarrationPlayback(client, creds).start(itemId)
        val session = (r as? AbsorbHttpClient.Result.Ok)?.value ?: return null
        return session.copy(id = "kept") to DownloadedBook.KIND_NARRATION
    }

    /** Pull every audio file of a narrated EPUB into the narration store.
     *  Files already there are skipped, so a pull can be resumed. */
    internal suspend fun pullNarration(itemId: String, trackCount: Int, onFile: (done: Int) -> Unit = {}) {
        if (AbsNarration.book(itemId) == null) {
            // After a restart the timings are on disk but the zip is not open.
            val creds = CredentialStore.credentials.value ?: return
            AbsNarration.reopen(creds, itemId) ?: return
        }
        for (i in 0 until trackCount) {
            try {
                AbsNarration.trackFile(itemId, i)
            } catch (e: Exception) {
                Log.w(TAG, "narration file $i of $itemId not pulled", e)
                _progress.update { it + (itemId to Progress(i.toFloat() / trackCount, complete = false, failed = true)) }
                return
            }
            _progress.update { it + (itemId to Progress((i + 1).toFloat() / trackCount, complete = i + 1 == trackCount)) }
            onFile(i + 1)
        }
    }

    /** Recompute one book's progress from the download manager or the files. */
    private fun refresh(key: String) {
        val book = DownloadStore[key] ?: return
        val p = when {
            book.isUrl -> {
                val m = downloadManager ?: return
                val byId = m.currentDownloads.associateBy { it.request.id }
                var done = 0
                var failed = false
                var fraction = 0f
                for (id in book.downloadIds) {
                    val d = byId[id] ?: m.downloadIndex.getDownload(id)
                    when (d?.state) {
                        Download.STATE_COMPLETED -> { done++; fraction += 1f }
                        Download.STATE_FAILED -> failed = true
                        null -> Unit
                        else -> fraction += (d.percentDownloaded.coerceIn(0f, 100f) / 100f)
                    }
                }
                val n = book.downloadIds.size.coerceAtLeast(1)
                Progress(fraction / n, complete = done == book.downloadIds.size, failed = failed)
            }
            else -> {
                val have = AbsNarration.cachedTrackCount(book.itemId, book.session.audioTracks.size)
                Progress(have.toFloat() / book.session.audioTracks.size.coerceAtLeast(1), complete = have == book.session.audioTracks.size)
            }
        }
        _progress.update { it + (key to p) }
    }

    private fun refreshAll() {
        DownloadStore.books.value.keys.forEach { refresh(it) }
        // Covers for books kept before covers were kept, or whose fetch failed.
        CredentialStore.credentials.value?.let { creds ->
            DownloadStore.books.value.values
                .filter { it.serverUrl == creds.serverUrl && CoverStore.existing(it.itemId) == null }
                .forEach { book -> scope.launch { CoverStore.save(creds, book.itemId) } }
        }
        // A narrated book interrupted mid-pull carries on.
        DownloadStore.books.value.values.filter { it.isNarration }.forEach { book ->
            if (_progress.value[book.key]?.complete != true && CredentialStore.credentials.value != null) {
                NarrationPullService.start(appContext, book.itemId, book.session.audioTracks.size)
            }
        }
    }

    @Serializable
    private data class AbsItem(val media: Media? = null) {
        @Serializable
        data class Media(
            val audioFiles: List<AudioFile> = emptyList(),
            val chapters: List<Chapter> = emptyList(),
            val duration: Double = 0.0,
        )

        @Serializable
        data class AudioFile(
            val index: Int = 0,
            val ino: String,
            val duration: Double = 0.0,
            val mimeType: String? = null,
        )
    }
}
