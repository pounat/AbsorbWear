package com.barnabas.absorb.wear.auth

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Minimal sign-in client for the watch's manual login flow: the password
 * login endpoint of Audiobookshelf (`/login`) or BookOrbit
 * (`/api/v1/auth/login`). Standalone library browse and playback live in
 * the `api` and `playback` packages.
 *
 * Uses java.net.HttpURLConnection on purpose: avoids dragging in OkHttp
 * (~1.5 MB) for a single endpoint.
 */
object AbsorbApi {

    private const val TAG = "AbsorbWearAuth"
    private const val NOT_FOUND = "Server returned HTTP 404"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    sealed class LoginError(val message: String) {
        object Unreachable : LoginError("Cannot reach server")
        object NoLoginRoute : LoginError("Neither Audiobookshelf nor BookOrbit answers at this address")
        object InvalidCredentials : LoginError("Wrong username or password")
        object MalformedResponse : LoginError("Server returned unexpected response")
        object PasswordsOff : LoginError("This server signs in without passwords. Sign in on your phone instead.")
        object DefaultPassword : LoginError("Change the starting password of this account in BookOrbit first.")
        data class Other(val msg: String) : LoginError(msg)
    }

    /**
     * Sign in with a password. The server type the user picked is tried
     * first; a 404 means the login route does not exist there, so the other
     * type is tried before giving up (an Audiobookshelf address pointed at a
     * BookOrbit server, or the reverse). An address with no scheme is tried
     * as https, then as plain http when it is a LAN address, since home
     * servers on a bare IP rarely have a certificate.
     */
    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        backend: String = Credentials.BACKEND_ABS,
    ): Result<Credentials> = withContext(Dispatchers.IO) {
        val bases = candidateBaseUrls(serverUrl)
            ?: return@withContext Result.failure(IOException(LoginError.Unreachable.message))
        val other = if (backend == Credentials.BACKEND_BOOKORBIT) Credentials.BACKEND_ABS else Credentials.BACKEND_BOOKORBIT
        var last: Result<Credentials>? = null
        for (base in bases) {
            val first = loginAt(base, username, password, backend)
            if (first.isSuccess) return@withContext first
            last = first
            Log.i(TAG, "Login at $base as $backend: ${first.exceptionOrNull()?.message}")
            val reason = first.exceptionOrNull()?.message.orEmpty()
            if (reason == LoginError.Unreachable.message) continue
            if (reason != NOT_FOUND) return@withContext first
            val second = loginAt(base, username, password, other)
            Log.i(TAG, "Login at $base as $other: ${second.exceptionOrNull()?.message ?: "ok"}")
            if (second.isSuccess || second.exceptionOrNull()?.message != NOT_FOUND) return@withContext second
            last = Result.failure(IOException(LoginError.NoLoginRoute.message))
        }
        last ?: Result.failure(IOException(LoginError.Unreachable.message))
    }

    private suspend fun loginAt(
        base: String,
        username: String,
        password: String,
        backend: String,
    ): Result<Credentials> = withContext(Dispatchers.IO) {
        val bookOrbit = backend == Credentials.BACKEND_BOOKORBIT

        val body = if (bookOrbit) {
            json.encodeToString(
                BookOrbitLoginRequest.serializer(),
                BookOrbitLoginRequest(username = username, password = password, deviceLabel = deviceLabel()),
            )
        } else {
            json.encodeToString(LoginRequest.serializer(), LoginRequest(username = username, password = password))
        }.toByteArray(Charsets.UTF_8)

        val conn = try {
            val url = if (bookOrbit) "$base/api/v1/auth/login" else "$base/login"
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (!bookOrbit) setRequestProperty("x-return-tokens", "true")
                setRequestProperty("User-Agent", "AbsorbWear/0.2.0")
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IOException(LoginError.Unreachable.message, e))
        }

        try {
            conn.outputStream.use { it.write(body) }

            val code = try {
                conn.responseCode
            } catch (e: UnknownHostException) {
                return@withContext Result.failure(IOException(LoginError.Unreachable.message, e))
            } catch (e: IOException) {
                return@withContext Result.failure(IOException(LoginError.Unreachable.message, e))
            }

            if (code == 401 || code == 403) {
                val detail = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                val error = if (bookOrbit && code == 403 && detail.contains("password_auth_disabled")) {
                    LoginError.PasswordsOff
                } else {
                    LoginError.InvalidCredentials
                }
                return@withContext Result.failure(IOException(error.message))
            }
            if (code == 404) {
                return@withContext Result.failure(IOException(NOT_FOUND))
            }
            if (code !in 200..299) {
                return@withContext Result.failure(IOException("Server returned HTTP $code"))
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = try {
                json.decodeFromString(LoginResponse.serializer(), responseText)
            } catch (e: SerializationException) {
                return@withContext Result.failure(IOException(LoginError.MalformedResponse.message, e))
            }

            val user = parsed.user
            val token = user?.accessToken ?: parsed.accessToken ?: user?.token
            if (token == null || user == null) {
                return@withContext Result.failure(IOException(LoginError.MalformedResponse.message))
            }
            if (bookOrbit && user.isDefaultPassword == true) {
                return@withContext Result.failure(IOException(LoginError.DefaultPassword.message))
            }

            Result.success(
                Credentials(
                    serverUrl = base,
                    accessToken = token,
                    refreshToken = user.refreshToken ?: parsed.refreshToken,
                    username = user.username ?: username,
                    userId = user.id?.content,
                    // No refreshToken on the modern endpoint means we got a
                    // legacy user.token instead: mirror AuthProvider's logic.
                    isLegacyToken = !bookOrbit && user.accessToken == null && parsed.accessToken == null,
                    source = "manual",
                    supportsTokenReturn = false,
                    backend = if (bookOrbit) Credentials.BACKEND_BOOKORBIT else Credentials.BACKEND_ABS,
                    issuedAt = System.currentTimeMillis(),
                )
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceLabel(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
        val label = if (model.isEmpty()) "Absorb" else "Absorb on $model"
        return label.take(100)
    }

    /** The base URLs to try for what the user typed, in order. */
    private fun candidateBaseUrls(raw: String): List<String>? {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null
        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        val candidates = if (hasScheme) {
            listOf(trimmed)
        } else {
            val host = trimmed.substringBefore('/').substringBefore(':')
            if (isLanAddress(host)) listOf("https://$trimmed", "http://$trimmed") else listOf("https://$trimmed")
        }
        return candidates.filter { runCatching { URL(it) }.isSuccess }.ifEmpty { null }
    }

    private fun isLanAddress(host: String): Boolean {
        if (host.equals("localhost", true) || host.endsWith(".local", true) || host.endsWith(".lan", true)) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || a == 127
    }

    @Serializable
    private data class LoginRequest(val username: String, val password: String)

    @Serializable
    private data class BookOrbitLoginRequest(
        val username: String,
        val password: String,
        val clientKind: String = "native",
        val deviceLabel: String,
    )

    @Serializable
    private data class LoginResponse(
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val user: UserDto? = null,
    )

    @Serializable
    private data class UserDto(
        // A string on Audiobookshelf, a number on BookOrbit.
        val id: JsonPrimitive? = null,
        val username: String? = null,
        // Older ABS servers return the legacy bearer at user.token instead
        // of the top-level accessToken field.
        val token: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val isDefaultPassword: Boolean? = null,
    )
}
