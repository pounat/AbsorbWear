package com.barnabas.absorb.wear.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.barnabas.absorb.wear.playback.AbsNarration
import kotlin.math.min

/**
 * Serves `absorbnarration://<itemId>/<index>` tracks: the audio file inside a
 * narrated EPUB on the server. A track kept on the watch plays from its
 * file; otherwise it is read as a byte range of the EPUB on the server, so
 * it streams and seeks like any other stream and goes through the same
 * cache. Everything else goes to the usual network source.
 */
@UnstableApi
class NarrationTrackDataSource private constructor(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = NarrationTrackDataSource(upstream.createDataSource())
    }

    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null
    private var reportedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        reportedUri = null
        if (dataSpec.uri.scheme != AbsNarration.SCHEME) {
            active = upstream
            return upstream.open(dataSpec)
        }
        val itemId = dataSpec.uri.host ?: throw IllegalArgumentException("narration uri without item: ${dataSpec.uri}")
        val index = dataSpec.uri.pathSegments.firstOrNull()?.toIntOrNull()
            ?: throw IllegalArgumentException("narration uri without track: ${dataSpec.uri}")
        // Blocking work on first use; ExoPlayer opens sources on its own
        // loading thread.
        val span = AbsNarration.trackSpan(itemId, index)
        if (span != null) {
            if (dataSpec.position > span.size) throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            val remaining = span.size - dataSpec.position
            val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else min(dataSpec.length, remaining)
            // The extractor picks its parser from the name it is given, so
            // the track's own name is reported rather than the EPUB's.
            reportedUri = Uri.parse("${dataSpec.uri}/${span.href.substringAfterLast('/')}")
            val ranged = dataSpec.buildUpon()
                .setUri(Uri.parse(span.url))
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + span.headers)
                .setPosition(span.offset + dataSpec.position)
                .setLength(length)
                .build()
            active = upstream
            return upstream.open(ranged)
        }
        val file = AbsNarration.trackFile(itemId, index)
        val source = FileDataSource()
        listeners.forEach { source.addTransferListener(it) }
        active = source
        return source.open(dataSpec.buildUpon().setUri(Uri.fromFile(file)).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IllegalStateException("not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = reportedUri ?: active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val headers = active?.responseHeaders ?: emptyMap()
        // The EPUB's own content type must not steer the extractor.
        return if (reportedUri != null) headers.filterKeys { !it.equals("Content-Type", ignoreCase = true) } else headers
    }

    override fun close() {
        val a = active ?: return
        active = null
        a.close()
    }
}
