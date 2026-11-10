package com.barnabas.absorb.wear.auth

import android.util.Log
import com.barnabas.absorb.wear.sync.PhoneAuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * On-watch access-token refresh.
 *
 * Audiobookshelf: `POST /auth/refresh` with the refresh token in
 * `x-refresh-token`, the way the phone client does it. BookOrbit:
 * `POST /api/v1/auth/refresh` with `{"refreshToken": ...}` in the body (or
 * the `refresh_token` cookie for a magic-link session, whose stored token
 * carries the `web:` prefix).
 *
 * ABS JWTs expire mid-listen on long sessions; without this the watch's
 * progress syncs silently 401 forever once the phone isn't around to re-push
 * credentials. BookOrbit tokens only last fifteen minutes, so callers there
 * also [ensureFresh] before a request instead of waiting for the 401. A
 * mutex serialises concurrent callers so only one refresh hits the server.
 *
 * A BookOrbit session shared with the phone is never refreshed here. BookOrbit
 * ends the whole session when a refresh token it already swapped is sent again
 * more than 30 seconds later, and the phone may have swapped it without the
 * watch hearing yet, so the watch asks the phone to renew it instead.
 */
object TokenRefresher {

    private const val TAG = "AbsorbWearAuth"

    /** Headroom before the token's own expiry at which we stop trusting it:
     *  a slow request that starts valid can still arrive expired. */
    private const val EXPIRY_SKEW_MS = 60_000L

    private const val WEB_REFRESH_PREFIX = "web:"

    /** How long to wait for the phone's pair after its own refresh token
     *  was turned down (the phone has usually pushed one already). */
    private const val PHONE_PUSH_WAIT_MS = 5_000L

    /** How long to wait while the phone renews a shared BookOrbit session:
     *  its own round trip to the server, then the push back. */
    private const val PHONE_RENEW_WAIT_MS = 15_000L

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshCookie = Regex("refresh_token=([^;,\\s]+)")

    /**
     * [creds] with an access token good for at least another minute,
     * refreshing first when it is about to run out. Returns the input (or the
     * store's newer copy of it) when the token has no readable expiry or the
     * refresh isn't possible; the request then runs and hits the 401 path.
     */
    suspend fun ensureFresh(creds: Credentials): Credentials {
        val current = CredentialStore.credentials.value
            ?.takeIf { it.serverUrl == creds.serverUrl }
            ?: creds
        val expiresAt = jwtExpiryMillis(current.accessToken) ?: return current
        if (System.currentTimeMillis() + EXPIRY_SKEW_MS < expiresAt) return current
        return refresh(current) ?: current
    }

    /**
     * Exchange [stale] for a fresh session. Returns the updated credentials
     * (also written to [CredentialStore]), or null when refresh isn't
     * possible (legacy token / API key) or the server rejected it.
     */
    suspend fun refresh(stale: Credentials): Credentials? = mutex.withLock {
        val current = CredentialStore.credentials.value ?: stale
        // Another caller already refreshed while we waited on the lock.
        if (current.accessToken != stale.accessToken) return@withLock current
        val refreshToken = current.refreshToken
        if (current.isLegacyToken || refreshToken == null) return@withLock null

        val phoneClient = if (current.source == "phone") {
            if (!current.supportsTokenReturn) return@withLock null
            PhoneAuthClient(CredentialStore.applicationContext).also {
                // Never spend the phone's shared refresh token unless a paired
                // phone is reachable to receive the rotated pair immediately.
                if (!it.canReachPhone()) return@withLock null
            }
        } else {
            null
        }
        if (phoneClient != null && current.isBookOrbit) {
            return@withLock awaitPhonePush(phoneClient, current, PHONE_RENEW_WAIT_MS)
        }

        withContext(Dispatchers.IO) {
            val base = current.serverUrl.removeSuffix("/")
            val webSession = refreshToken.startsWith(WEB_REFRESH_PREFIX)
            val body: String? = when {
                !current.isBookOrbit -> null
                webSession -> "{}"
                else -> json.encodeToString(
                    BookOrbitRefreshBody.serializer(),
                    BookOrbitRefreshBody(refreshToken),
                )
            }
            val conn = try {
                val url = if (current.isBookOrbit) "$base/api/v1/auth/refresh" else "$base/auth/refresh"
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "AbsorbWear/0.2.0")
                    when {
                        !current.isBookOrbit -> setRequestProperty("x-refresh-token", refreshToken)
                        webSession -> setRequestProperty(
                            "Cookie",
                            "refresh_token=" + refreshToken.removePrefix(WEB_REFRESH_PREFIX),
                        )
                    }
                    current.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh: cannot open connection", e)
                return@withContext null
            }
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Token refresh rejected: HTTP $code")
                    // The phone may have rotated the shared session first.
                    // Ask it for its pair and wait a moment for the push.
                    if (phoneClient != null && (code == 401 || code == 403)) {
                        return@withContext awaitPhonePush(phoneClient, current, PHONE_PUSH_WAIT_MS)
                    }
                    return@withContext null
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val tokens = parseWearRefreshTokens(
                    text,
                    allowMissingRefreshToken = current.isBookOrbit,
                ) ?: run {
                    Log.w(TAG, "Token refresh response had no complete token pair")
                    return@withContext null
                }
                // A magic-link session's next refresh token comes back as a cookie.
                val rotatedWebToken = if (webSession) {
                    conn.headerFields["Set-Cookie"]
                        ?.firstNotNullOfOrNull { refreshCookie.find(it)?.groupValues?.get(1) }
                        ?.let { WEB_REFRESH_PREFIX + it }
                } else {
                    null
                }
                val updated = current.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = rotatedWebToken ?: tokens.refreshToken ?: refreshToken,
                    issuedAt = System.currentTimeMillis(),
                )
                CredentialStore.update(updated)
                if (phoneClient != null) {
                    val returned = phoneClient.returnRotatedCredentials(updated)
                    if (returned !is PhoneAuthClient.SendResult.Delivered) {
                        Log.w(TAG, "Rotated credentials could not be returned to phone")
                    }
                }
                Log.i(TAG, "Access token refreshed on-watch")
                updated
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh failed", e)
                null
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Request the phone's current session and wait up to [waitMs] for a
     *  token different from [stale], and not about to run out, to land in
     *  the store. */
    private suspend fun awaitPhonePush(phone: PhoneAuthClient, stale: Credentials, waitMs: Long): Credentials? {
        if (phone.requestSync() !is PhoneAuthClient.SendResult.Delivered) {
            Log.w(TAG, "Could not ask the phone for a fresh session")
            return null
        }
        repeat((waitMs / 250).toInt()) {
            delay(250)
            val now = CredentialStore.credentials.value ?: return null
            if (now.serverUrl == stale.serverUrl && now.accessToken != stale.accessToken) {
                val expiresAt = jwtExpiryMillis(now.accessToken)
                if (expiresAt == null || System.currentTimeMillis() + EXPIRY_SKEW_MS < expiresAt) {
                    Log.i(TAG, "Took the newer session the phone pushed")
                    return now
                }
            }
        }
        Log.w(TAG, "The phone sent no fresh session in ${waitMs / 1000}s")
        return null
    }

    @Serializable
    private data class BookOrbitRefreshBody(val refreshToken: String)
}

internal data class WearRefreshTokens(
    val accessToken: String,
    /** Null when the server kept the old refresh token (allowed on BookOrbit). */
    val refreshToken: String?,
)

private val refreshResponseJson = Json { ignoreUnknownKeys = true }

internal fun parseWearRefreshTokens(
    body: String,
    allowMissingRefreshToken: Boolean = false,
): WearRefreshTokens? {
    val parsed = refreshResponseJson.decodeFromString(RefreshResponse.serializer(), body)
    val accessToken = parsed.user?.accessToken ?: parsed.accessToken ?: return null
    val refreshToken = parsed.user?.refreshToken ?: parsed.refreshToken
    if (refreshToken == null && !allowMissingRefreshToken) return null
    return WearRefreshTokens(accessToken, refreshToken)
}

/** Epoch millis stamped in a JWT's `exp`, or null when the token isn't one
 *  (legacy static tokens, API keys, opaque tokens). */
internal fun jwtExpiryMillis(token: String): Long? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    return try {
        val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
        Json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.times(1000)
    } catch (_: Exception) {
        null
    }
}

@Serializable
private data class RefreshResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val user: RefreshUser? = null,
)

@Serializable
private data class RefreshUser(
    val accessToken: String? = null,
    val refreshToken: String? = null,
)
