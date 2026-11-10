package com.barnabas.absorb.wear.auth

import kotlinx.serialization.Serializable

/**
 * Snapshot of an Audiobookshelf session. Mirrors the fields the phone
 * AuthProvider persists to SharedPreferences and pushes over the Data
 * Layer when the user logs in (or refreshes tokens).
 */
@Serializable
data class Credentials(
    val serverUrl: String,
    val accessToken: String,
    /** Null for legacy / API-key sessions; ABS only issues a refresh token
     *  for the JWT flow on modern servers. */
    val refreshToken: String? = null,
    val username: String,
    val userId: String? = null,
    /** True for old servers that return a non-expiring `user.token` instead
     *  of `accessToken` + `refreshToken`. The watch needs to know so it
     *  doesn't try to call the refresh endpoint. */
    val isLegacyToken: Boolean = false,
    /** Headers the phone-side AuthProvider attaches to every request —
     *  typically Cloudflare Access (`CF-Access-Client-Id`, `…-Secret`) or
     *  Authelia / proxy auth tokens. Empty for direct-exposed servers.
     *  Must be replayed on every outbound call from the watch or the
     *  proxy returns 403. */
    val customHeaders: Map<String, String> = emptyMap(),
    /** `phone` credentials share a refresh session with Absorb. They may only
     * rotate when the phone advertises that it can adopt the returned pair. */
    val source: String = "phone",
    val supportsTokenReturn: Boolean = false,
    /** Which kind of server this is: "abs" (Audiobookshelf) or "bookorbit".
     *  Old phone payloads carry no field and are Audiobookshelf. */
    val backend: String = "abs",
    /** Epoch millis when the phone wrote this snapshot. Used as a
     *  tiebreaker if multiple DataItems arrive out-of-order. */
    val issuedAt: Long = 0L,
) {
    val isBookOrbit: Boolean get() = backend == BACKEND_BOOKORBIT

    companion object {
        const val BACKEND_ABS = "abs"
        const val BACKEND_BOOKORBIT = "bookorbit"
    }
}
