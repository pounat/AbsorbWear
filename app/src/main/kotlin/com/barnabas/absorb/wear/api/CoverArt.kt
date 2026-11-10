package com.barnabas.absorb.wear.api

import com.barnabas.absorb.wear.auth.Credentials

/**
 * Builds cover-art URLs for direct fetch from the watch.
 *
 * Audiobookshelf serves `/api/items/:id/cover`; BookOrbit serves a
 * watch-sized square `/api/v1/books/:id/thumbnail`. Auth (bearer token + any
 * reverse-proxy headers) is attached as request headers by the caller, not
 * embedded here. We request a watch-sized width so we don't pull
 * full-resolution covers over a possibly Bluetooth-proxied connection — ABS
 * resizes server-side and ignores the param on older versions, so it's
 * always safe.
 */
object CoverArt {

    fun url(creds: Credentials, itemId: String, width: Int = 240): String {
        val base = creds.serverUrl.removeSuffix("/")
        return if (creds.isBookOrbit) {
            "$base/api/v1/books/$itemId/thumbnail?medium=audio"
        } else {
            "$base/api/items/$itemId/cover?width=$width"
        }
    }
}
