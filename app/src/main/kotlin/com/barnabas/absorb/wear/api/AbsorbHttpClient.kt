package com.barnabas.absorb.wear.api

import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.auth.TokenRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Authenticated HTTP client for the watch's direct server calls, to
 * Audiobookshelf or BookOrbit alike (callers pass the full path).
 *
 * Pulls bearer token and any reverse-proxy headers (Cloudflare Access /
 * Authelia / etc.) from [Credentials], so it works in any of the modes
 * the phone app supports: JWT, legacy `user.token`, or API key.
 *
 * Always reads the *latest* credentials from [CredentialStore] (falling back
 * to the snapshot it was built with) so long-running loops like the 15s
 * progress sync survive a token rotation. A 401 triggers one on-watch
 * refresh via [TokenRefresher] and a single retry, mirroring the phone
 * client's behaviour. BookOrbit tokens only last fifteen minutes, so there
 * the token is also refreshed ahead of a request that would arrive expired.
 *
 * Stays on HttpURLConnection: no OkHttp dependency for a couple of verbs.
 * Note: this is for the JSON API (bearer header). ABS audio *streams*
 * authenticate with a `?token=` query param instead, see StreamUrl.
 */
class AbsorbHttpClient(private val initialCreds: Credentials) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Identifies the server for per-server caches. */
    val serverKey: String get() = initialCreds.serverUrl.removeSuffix("/").lowercase()

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Unauthorized<T>(val code: Int) : Result<T>()
        /** [code] is the HTTP status, or 0 when the server could not be reached. */
        data class Failed<T>(val message: String, val code: Int = 0) : Result<T>()
    }

    suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
    ): Result<T> = request("GET", path, null) { decode(it, deserializer) }

    /** POST a JSON [body] and deserialize the JSON response. */
    suspend fun <T> post(
        path: String,
        body: String,
        deserializer: DeserializationStrategy<T>,
    ): Result<T> = request("POST", path, body) { decode(it, deserializer) }

    /** PUT a JSON [body] and deserialize the JSON response. */
    suspend fun <T> put(
        path: String,
        body: String,
        deserializer: DeserializationStrategy<T>,
    ): Result<T> = request("PUT", path, body) { decode(it, deserializer) }

    /** POST where we only care about success (sync / close). [body] may be empty. */
    suspend fun postForStatus(path: String, body: String = ""): Result<Unit> =
        request("POST", path, body.takeIf { it.isNotEmpty() }) { Result.Ok(Unit) }

    /** PUT a JSON [body] where we only care about success. */
    suspend fun putForStatus(path: String, body: String): Result<Unit> =
        request("PUT", path, body) { Result.Ok(Unit) }

    /** PATCH a JSON [body] where we only care about success. Android's
     *  HttpURLConnection accepts PATCH as a method. */
    suspend fun patchForStatus(path: String, body: String): Result<Unit> =
        request("PATCH", path, body) { Result.Ok(Unit) }

    suspend fun deleteForStatus(path: String): Result<Unit> =
        request("DELETE", path, null) { Result.Ok(Unit) }

    private suspend fun <T> request(
        method: String,
        path: String,
        body: String?,
        parse: (String) -> Result<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        var creds = currentCreds()
        if (creds.isBookOrbit) creds = TokenRefresher.ensureFresh(creds)
        var raw = execute(method, path, body, creds)
        if (raw is Raw.Http && raw.code == 401) {
            TokenRefresher.refresh(creds)?.let { fresh ->
                creds = fresh
                raw = execute(method, path, body, creds)
            }
        }
        when (val r = raw) {
            is Raw.Network -> Result.Failed(r.message)
            is Raw.Http -> when {
                r.code == 401 || r.code == 403 -> Result.Unauthorized(r.code)
                r.code !in 200..299 -> Result.Failed("HTTP ${r.code}", r.code)
                else -> parse(r.body)
            }
        }
    }

    private sealed class Raw {
        data class Http(val code: Int, val body: String) : Raw()
        data class Network(val message: String) : Raw()
    }

    private fun execute(method: String, path: String, body: String?, creds: Credentials): Raw {
        val conn = try {
            openConnection(path, method, creds)
        } catch (e: Exception) {
            return Raw.Network("Cannot reach server")
        }
        return try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else ""
            Raw.Http(code, text)
        } catch (e: IOException) {
            Raw.Network(e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    private fun currentCreds(): Credentials {
        val live = CredentialStore.credentials.value ?: return initialCreds
        // Only trust the store if it's still the same account/server.
        return if (live.serverUrl == initialCreds.serverUrl) live else initialCreds
    }

    private fun <T> decode(body: String, deserializer: DeserializationStrategy<T>): Result<T> =
        try {
            Result.Ok(json.decodeFromString(deserializer, body))
        } catch (e: SerializationException) {
            Result.Failed("Bad response shape")
        } catch (e: IllegalArgumentException) {
            Result.Failed("Bad response shape")
        }

    private fun openConnection(path: String, method: String, creds: Credentials): HttpURLConnection {
        val base = creds.serverUrl.removeSuffix("/")
        val url = URL(base + (if (path.startsWith("/")) path else "/$path"))
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            // Play-session opens can be slow on big books (server builds the
            // track list), so give reads the same 20s the phone client allows.
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${creds.accessToken}")
            setRequestProperty("User-Agent", "AbsorbWear/0.2.0")
            creds.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    }
}
