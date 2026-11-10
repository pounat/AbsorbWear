package com.barnabas.absorb.wear.playback

import com.barnabas.absorb.wear.api.CoverArt
import com.barnabas.absorb.wear.auth.Credentials

/**
 * Builds absolute, authenticated audio-stream URLs from a track's
 * server-relative `contentUrl`.
 *
 * Mirrors the Flutter client's `buildTrackUrl`: ABS media streams
 * authenticate with a `?token=` query param, NOT the Authorization header —
 * the token in the query survives reverse-proxy redirects that would strip
 * a header. Reverse-proxy [Credentials.customHeaders] (Cloudflare etc.) are
 * applied separately, on the ExoPlayer data source.
 *
 * BookOrbit is the other way round: it only takes a Bearer header and its
 * access tokens last fifteen minutes, so its URLs carry no token at all and
 * the media service's data source adds a fresh header to every request.
 */
object StreamUrl {

    fun forTrack(creds: Credentials, contentUrl: String): String {
        // A narrated EPUB's audio is served from the cache by its own scheme.
        if (contentUrl.startsWith("${AbsNarration.SCHEME}://")) return contentUrl
        if (contentUrl.startsWith("http")) return withToken(contentUrl, creds)
        val base = creds.serverUrl.removeSuffix("/")
        // Servers behind ROUTER_BASE_PATH return contentUrls that already
        // include the base path (e.g. "/abs/public/session/…"). If the server
        // URL also ends with it, blind concatenation doubles it ("/abs/abs/…"),
        // so join against the origin instead — same logic as the phone
        // client's buildTrackUrl.
        val absolute = try {
            val uri = java.net.URI(base)
            val basePath = uri.rawPath.orEmpty()
            if (basePath.isNotEmpty() && basePath != "/" && contentUrl.startsWith("$basePath/")) {
                "${uri.scheme}://${uri.rawAuthority}$contentUrl"
            } else {
                base + contentUrl
            }
        } catch (_: Exception) {
            base + contentUrl
        }
        return withToken(absolute, creds)
    }

    /** Cover URL for the media-notification artwork (fetched by Media3's own
     *  loader, not our Coil one). Carries the token in the query on ABS; on
     *  BookOrbit the data source adds the header instead. */
    fun forCover(creds: Credentials, itemId: String, width: Int = 240): String =
        withToken(CoverArt.url(creds, itemId, width), creds)

    private fun withToken(url: String, creds: Credentials): String {
        if (creds.isBookOrbit) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}token=${creds.accessToken}"
    }
}
