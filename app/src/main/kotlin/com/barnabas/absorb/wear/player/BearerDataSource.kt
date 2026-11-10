package com.barnabas.absorb.wear.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.auth.TokenRefresher
import kotlinx.coroutines.runBlocking

/**
 * HTTP data source that signs BookOrbit media requests.
 *
 * BookOrbit only takes a Bearer header (no token in the query string) and
 * its access tokens last fifteen minutes, so a long listen crosses several
 * expiries. Every open — the first track, a seek, the next chapter file, the
 * notification artwork — gets a token that is fresh right now, and a 401 is
 * answered with one refresh and a retry. ABS streams carry `?token=` in the
 * URL and pass straight through.
 */
@UnstableApi
class BearerDataSource private constructor(private val delegate: HttpDataSource) : DataSource {

    class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = BearerDataSource(upstream.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val creds = CredentialStore.credentials.value
        if (creds == null || !creds.isBookOrbit || !sameServer(creds, dataSpec.uri)) {
            return delegate.open(dataSpec)
        }
        // Blocking here is fine: ExoPlayer opens sources on its own loading
        // thread, never the main thread.
        val fresh = runBlocking { TokenRefresher.ensureFresh(creds) }
        return try {
            delegate.open(withBearer(dataSpec, fresh))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 401) throw e
            runCatching { delegate.close() }
            // Refresh, or take a newer token the phone pushed meanwhile (the
            // two share one session and can rotate it from either side).
            val refreshed = runBlocking { TokenRefresher.refresh(fresh) }
                ?: CredentialStore.credentials.value?.takeIf {
                    it.serverUrl == fresh.serverUrl && it.accessToken != fresh.accessToken
                }
                ?: throw e
            delegate.open(withBearer(dataSpec, refreshed))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() = delegate.close()

    private fun withBearer(dataSpec: DataSpec, creds: Credentials): DataSpec =
        dataSpec.buildUpon()
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Authorization" to "Bearer ${creds.accessToken}"))
            .build()

    private fun sameServer(creds: Credentials, uri: Uri): Boolean =
        uri.host.equals(Uri.parse(creds.serverUrl).host, ignoreCase = true)
}
