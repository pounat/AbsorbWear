package com.barnabas.absorb.wear.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Two on-disk caches, keyed by the URL without the ABS `token` query (which
 * rotates with the session and would otherwise split one file into many
 * entries):
 *
 * - [streams]: what the player fetched lately, bounded and least-recently
 *   used, so a book just played does not buffer from the server again and
 *   a seek back lands on bytes already here. Lives in the cache folder.
 * - [downloads]: whole books the user chose to keep, never evicted, removed
 *   only by the user. Lives in the files folder so the system does not
 *   clear it. The player reads it first, read-only.
 *
 * One SimpleCache per folder per process, as Media3 requires.
 */
@UnstableApi
object MediaCache {

    private const val STREAM_MAX_BYTES = 512L * 1024 * 1024

    private var streams: SimpleCache? = null
    private var downloads: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null

    @Synchronized
    private fun db(context: Context): StandaloneDatabaseProvider =
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    @Synchronized
    fun streams(context: Context): SimpleCache = streams ?: SimpleCache(
        File(context.applicationContext.cacheDir, "media-stream"),
        LeastRecentlyUsedCacheEvictor(STREAM_MAX_BYTES),
        db(context),
    ).also { streams = it }

    @Synchronized
    fun downloads(context: Context): SimpleCache = downloads ?: SimpleCache(
        File(context.applicationContext.filesDir, "media-downloads"),
        NoOpCacheEvictor(),
        db(context),
    ).also { downloads = it }

    /** The cache key for [uri]: the URL with any session token stripped. */
    fun cacheKey(uri: Uri): String {
        if (uri.getQueryParameter("token") == null) return uri.toString()
        val b = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "token") continue
            for (value in uri.getQueryParameters(name)) b.appendQueryParameter(name, value)
        }
        return b.build().toString()
    }
}
