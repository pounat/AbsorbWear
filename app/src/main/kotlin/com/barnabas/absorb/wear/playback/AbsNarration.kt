package com.barnabas.absorb.wear.playback

import android.util.Log
import com.barnabas.absorb.wear.api.RemoteZip
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.auth.TokenRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrated EPUBs played out of the EPUB itself: on an Audiobookshelf server,
 * which only sees an ebook, and on BookOrbit for a book whose audio files
 * BookOrbit can't find by the names its own playlist gives them.
 *
 * The EPUB stays on the server: its index, package and timing files are read
 * with range requests, and its audio files are played as ranges of the EPUB
 * itself, through the same stream cache as any other stream, so a book
 * starts within seconds however its narration is split up. Keeping a book
 * on the watch pulls each audio file out into its own file. The timings are
 * kept on disk once read, since a long book's take a while.
 *
 * Tracks are addressed as `absorbnarration://<itemId>/<index>`, which
 * [com.barnabas.absorb.wear.player.NarrationTrackDataSource] turns into the
 * range or the kept file.
 */
object AbsNarration {

    private const val TAG = "AbsorbWearPlayback"
    const val SCHEME = "absorbnarration"
    private const val BOOKORBIT_PREFIX = "bookorbit-"

    class Book(val itemId: String, val url: String, val zip: RemoteZip, val overlay: NarrationOverlay, val dir: File)

    private val books = ConcurrentHashMap<String, Book>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val json = Json { ignoreUnknownKeys = true }

    fun trackUri(itemId: String, index: Int): String = "$SCHEME://$itemId/$index"

    fun book(itemId: String): Book? = books[itemId]

    /** Open [itemId]'s EPUB (file [ino] on the server) and read its narration. */
    suspend fun prepare(creds: Credentials, itemId: String, ino: String): Book = withContext(Dispatchers.IO) {
        books[itemId]?.let { return@withContext it }
        val base = creds.serverUrl.removeSuffix("/")
        val url = "$base/api/items/$itemId/file/$ino"
        val headers = headersFor(creds)
        val started = System.currentTimeMillis()
        val zip = RemoteZip.open(url, headers)
        val dir = bookDir(itemId).apply { mkdirs() }
        val snapshotFile = File(dir, "overlay-${safe(ino)}.json")
        val cached = snapshotFile.takeIf { it.exists() }?.let { f ->
            runCatching { NarrationOverlay.fromSnapshot(json.decodeFromString(NarrationOverlay.Snapshot.serializer(), f.readText())) }
                .onFailure { Log.w(TAG, "cached timings for $itemId unreadable, reading again", it) }
                .getOrNull()
        }
        val overlay = cached ?: run {
            val parsed = EpubOverlayParser.parse { name ->
                zip.entry(name)?.let { String(zip.read(it), Charsets.UTF_8) }
            }
            if (parsed == null || parsed.isEmpty) throw RemoteZip.RemoteZipException("This EPUB has no narration")
            runCatching { snapshotFile.writeText(json.encodeToString(NarrationOverlay.Snapshot.serializer(), parsed.snapshot())) }
            parsed
        }
        Log.i(
            TAG,
            "narration for $itemId ${if (cached != null) "from cache" else "read from the server"} in " +
                "${System.currentTimeMillis() - started}ms: ${overlay.tracks.size} file(s), ${overlay.clips.size} sentences, " +
                "${overlay.duration.toLong()}s (${zip.length} bytes, ${zip.entries.size} entries)",
        )
        Book(itemId, url, zip, overlay, dir).also { books[itemId] = it }
    }

    /**
     * Open BookOrbit book [bookId]'s EPUB (file [fileId]) to play its audio
     * from, with [overlay] read from BookOrbit's playlist. Throws when the
     * EPUB can't be read or is missing one of the overlay's files.
     */
    suspend fun prepareBookOrbit(creds: Credentials, bookId: String, fileId: String, overlay: NarrationOverlay): Book =
        withContext(Dispatchers.IO) {
            val base = creds.serverUrl.removeSuffix("/")
            val url = "$base/api/v1/books/files/$fileId/download"
            books[bookId]
                ?.takeIf { open -> open.url == url && open.overlay.tracks.map { it.href } == overlay.tracks.map { it.href } }
                ?.let { return@withContext it }
            val started = System.currentTimeMillis()
            val zip = RemoteZip.open(url, headersFor(creds))
            val missing = overlay.tracks.firstOrNull { entryFor(zip, it.href) == null }
            if (missing != null) throw RemoteZip.RemoteZipException("${missing.href} is not in the EPUB")
            val dir = bookDir(bookId).apply { mkdirs() }
            // Kept so a download cut short can open the book again after a restart.
            dir.listFiles()?.filter { it.name.startsWith(BOOKORBIT_PREFIX) }?.forEach { it.delete() }
            runCatching {
                File(dir, "$BOOKORBIT_PREFIX${safe(fileId)}.json")
                    .writeText(json.encodeToString(NarrationOverlay.Snapshot.serializer(), overlay.snapshot()))
            }
            Log.i(
                TAG,
                "EPUB for BookOrbit book $bookId opened in ${System.currentTimeMillis() - started}ms: " +
                    "${overlay.tracks.size} file(s) (${zip.length} bytes, ${zip.entries.size} entries)",
            )
            Book(bookId, url, zip, overlay, dir).also { books[bookId] = it }
        }

    /** Open a kept book again after a restart, from the timings [prepare]
     *  or [prepareBookOrbit] left on disk. Null when there are none. */
    suspend fun reopen(creds: Credentials, itemId: String): Book? {
        books[itemId]?.let { return it }
        inoOf(itemId)?.let { return prepare(creds, itemId, it) }
        val fileId = bookOrbitFileOf(itemId) ?: return null
        val file = File(bookDir(itemId), "$BOOKORBIT_PREFIX${safe(fileId)}.json")
        val overlay = withContext(Dispatchers.IO) {
            runCatching { NarrationOverlay.fromSnapshot(json.decodeFromString(NarrationOverlay.Snapshot.serializer(), file.readText())) }
                .onFailure { Log.w(TAG, "kept timings for $itemId unreadable", it) }
                .getOrNull()
        } ?: return null
        return prepareBookOrbit(creds, itemId, fileId, overlay)
    }

    /** The BookOrbit EPUB file a book's audio was last read out of, or null. */
    fun bookOrbitFileOf(itemId: String): String? {
        val name = bookDir(itemId).list()?.firstOrNull { it.startsWith(BOOKORBIT_PREFIX) && it.endsWith(".json") } ?: return null
        return name.removePrefix(BOOKORBIT_PREFIX).removeSuffix(".json")
    }

    /** The zip entry for [href]: as written, with its escapes undone (a
     *  playlist can keep a comma as %2C), or a path ending the same way. */
    internal fun entryFor(zip: RemoteZip, href: String): RemoteZip.Entry? {
        zip.entry(href)?.let { return it }
        val plain = NarrationOverlay.undoEscapes(href)
        return zip.entry(plain) ?: zip.entries.values.firstOrNull { NarrationOverlay.sameDoc(it.name, plain) }
    }

    /** Track [index] as a range of the EPUB on the server: the player reads
     *  it straight from there, like any stream, so a book whose narration is
     *  one file for the whole book still starts in seconds. */
    class Span(val url: String, val headers: Map<String, String>, val offset: Long, val size: Long, val href: String)

    /** [index]'s span when it should be streamed: null when the track is
     *  already on the watch, or is compressed inside the EPUB and has to be
     *  unpacked into a file first. Blocking the first time per track. */
    fun trackSpan(itemId: String, index: Int): Span? {
        val book = books[itemId]
            ?: if (keptTrack(itemId, index) != null) return null else throw RemoteZip.RemoteZipException("No narration open for $itemId")
        val track = book.overlay.tracks.getOrNull(index) ?: throw RemoteZip.RemoteZipException("No track $index")
        val entry = entryFor(book.zip, track.href) ?: throw RemoteZip.RemoteZipException("${track.href} is not in the EPUB")
        if (!entry.stored) return null
        val kept = File(book.dir, "track-$index.${ext(track.href)}")
        if (kept.exists() && kept.length() == entry.uncompressedSize) return null
        return Span(book.url, book.zip.requestHeaders(), book.zip.dataOffset(entry), entry.uncompressedSize, track.href)
    }

    /** The audio file for track [index], pulled from the EPUB on first use.
     *  A kept book plays from its files without the EPUB being open, as it
     *  isn't after a restart. Blocking; the player calls it from its loading
     *  thread. */
    fun trackFile(itemId: String, index: Int): File {
        val book = books[itemId]
            ?: return keptTrack(itemId, index) ?: throw RemoteZip.RemoteZipException("No narration open for $itemId")
        val track = book.overlay.tracks.getOrNull(index) ?: throw RemoteZip.RemoteZipException("No track $index")
        val entry = entryFor(book.zip, track.href) ?: throw RemoteZip.RemoteZipException("${track.href} is not in the EPUB")
        val out = File(book.dir, "track-$index.${ext(track.href)}")
        synchronized(locks.getOrPut("$itemId/$index") { Any() }) {
            if (out.exists() && out.length() == entry.uncompressedSize) return out
            val tmp = File(book.dir, "track-$index.part")
            val started = System.currentTimeMillis()
            try {
                book.zip.download(entry, tmp)
                if (!tmp.renameTo(out)) throw RemoteZip.RemoteZipException("could not keep ${out.name}")
            } finally {
                tmp.delete()
            }
            Log.i(TAG, "pulled ${track.href} (${entry.uncompressedSize} bytes) in ${System.currentTimeMillis() - started}ms")
            return out
        }
    }

    /** How many of [trackCount] audio files are already on the watch. */
    fun cachedTrackCount(itemId: String, trackCount: Int): Int {
        val dir = bookDir(itemId)
        if (!dir.exists()) return 0
        val names = dir.list()?.toSet() ?: return 0
        return (0 until trackCount).count { i -> names.any { it.startsWith("track-$i.") && !it.endsWith(".part") } }
    }

    /** The EPUB file id the timings on disk were read for, or null. */
    fun inoOf(itemId: String): String? {
        val dir = bookDir(itemId)
        val name = dir.list()?.firstOrNull { it.startsWith("overlay-") && it.endsWith(".json") } ?: return null
        return name.removePrefix("overlay-").removeSuffix(".json")
    }

    fun deleteCached(itemId: String) {
        books.remove(itemId)
        bookDir(itemId).deleteRecursively()
    }

    /** Read on every request, so a refreshed token is used. BookOrbit's
     *  tokens last fifteen minutes and are renewed before they run out. */
    private fun headersFor(creds: Credentials): () -> Map<String, String> = {
        val live = CredentialStore.credentials.value?.takeIf { it.serverUrl == creds.serverUrl } ?: creds
        val signed = if (live.isBookOrbit) runBlocking { TokenRefresher.ensureFresh(live) } else live
        signed.customHeaders + ("Authorization" to "Bearer ${signed.accessToken}")
    }

    private fun keptTrack(itemId: String, index: Int): File? =
        bookDir(itemId).listFiles()?.firstOrNull { it.name.startsWith("track-$index.") && !it.name.endsWith(".part") }

    /** In the files folder, not the cache folder: a kept book must survive
     *  the system clearing caches. */
    private fun bookDir(itemId: String): File =
        File(CredentialStore.applicationContext.filesDir, "narration/${safe(itemId)}")

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ext(href: String): String = href.substringAfterLast('.', "bin").take(5)
}
