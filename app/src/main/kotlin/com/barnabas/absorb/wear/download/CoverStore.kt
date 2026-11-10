package com.barnabas.absorb.wear.download

import android.util.Log
import com.barnabas.absorb.wear.api.CoverArt
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cover images kept with downloaded books, so the player and the list show
 * them with no server in reach. Small files in the app's files folder.
 */
object CoverStore {

    private const val TAG = "AbsorbWearDownload"

    fun file(itemId: String): File =
        File(CredentialStore.applicationContext.filesDir, "covers/${itemId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.img")

    /** The kept cover for [itemId], or null when there is none. */
    fun existing(itemId: String): File? = file(itemId).takeIf { it.exists() && it.length() > 0 }

    suspend fun save(creds: Credentials, itemId: String) = withContext(Dispatchers.IO) {
        val out = file(itemId)
        if (out.exists() && out.length() > 0) return@withContext
        out.parentFile?.mkdirs()
        val live = CredentialStore.credentials.value?.takeIf { it.serverUrl == creds.serverUrl } ?: creds
        val conn = try {
            (URL(CoverArt.url(live, itemId, width = 480)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Authorization", "Bearer ${live.accessToken}")
                setRequestProperty("User-Agent", "AbsorbWear/0.2.0")
                live.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cover for $itemId: cannot connect", e)
            return@withContext
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "cover for $itemId: HTTP ${conn.responseCode}")
                return@withContext
            }
            val tmp = File(out.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(out)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "cover for $itemId not kept", e)
        } finally {
            conn.disconnect()
        }
    }

    fun delete(itemId: String) {
        file(itemId).delete()
    }
}
