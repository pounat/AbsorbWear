package com.barnabas.absorb.wear.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * The BookOrbit listening sessions this watch has recorded, told to the
 * phone. BookOrbit keeps no device on a session, so the phone names the
 * sessions it recorded itself from a local list; this is the watch's half
 * of that list, published as a persisted DataItem so it arrives whenever
 * the two next meet and the phone's stats can show the watch icon.
 */
object WatchSessionLog {

    private const val TAG = "AbsorbWearPlayback"
    private const val PREFS = "absorb_wear_sessions"
    private const val KEY = "bookorbit"
    private const val LIMIT = 100

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Adds one session, keyed the way the phone keys its own, and
     *  republishes the list. A session already listed is left alone. */
    fun record(bookId: String, startedAt: String) {
        if (!::prefs.isInitialized) return
        scope.launch {
            val sessions = synchronized(this@WatchSessionLog) {
                val list = prefs.getString(KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
                for (i in 0 until list.length()) {
                    val s = list.optJSONObject(i) ?: continue
                    if (s.optString("bookId") == bookId && s.optString("startedAt") == startedAt) return@launch
                }
                list.put(JSONObject().put("bookId", bookId).put("startedAt", startedAt))
                while (list.length() > LIMIT) list.remove(0)
                prefs.edit().putString(KEY, list.toString()).apply()
                list
            }
            publish(sessions)
        }
    }

    private suspend fun publish(sessions: JSONArray) {
        val payload = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sessions", sessions)
            .toString()
        val request = PutDataMapRequest.create(WearPaths.PLAYER_SESSIONS).apply {
            dataMap.putByteArray("payload", payload.toByteArray(Charsets.UTF_8))
            dataMap.putLong("issuedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(appContext).putDataItem(request).await()
        } catch (e: Exception) {
            Log.w(TAG, "watch session list not published", e)
        }
    }
}
