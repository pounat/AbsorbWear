package com.barnabas.absorb.wear.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Process-wide singleton holding the watch's current session.
 *
 * Backed by EncryptedSharedPreferences so the bearer token is wrapped with
 * a key from the Android KeyStore: the on-disk blob is unreadable without
 * the device unlocked. The in-memory mirror is a StateFlow so Compose
 * screens can recompose the moment the phone pushes a fresh session over
 * the Data Layer.
 *
 * Opening the encrypted store takes over a second on a watch, so
 * Application.onCreate runs [init] off the main thread and the splash
 * screen stays up until [ready] flips. Services that may start first call
 * [init] themselves; it is idempotent and blocks only the first caller.
 */
object CredentialStore {
    private const val PREFS_NAME = "absorb_wear_creds"
    private const val KEY_JSON = "session"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    internal lateinit var applicationContext: Context
        private set

    private val _credentials = MutableStateFlow<Credentials?>(null)
    val credentials: StateFlow<Credentials?> = _credentials.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** True once the stored session has been read. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Make the application context available before [init] has run, for
     *  code that needs it (phone messaging, cache folders) early. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (!::applicationContext.isInitialized) applicationContext = context.applicationContext
        }
    }

    /** Open the store and read the session. Safe to call from any thread
     *  and any number of times. */
    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            applicationContext = context.applicationContext

            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val opened = EncryptedSharedPreferences.create(
                applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs = opened
            _credentials.value = readFromDisk(opened)
            _ready.value = true
        }
    }

    /** Replace the stored session. Pass null to clear (signed out). */
    fun update(creds: Credentials?) {
        val p = synchronized(lock) { prefs } ?: run {
            init(applicationContext)
            synchronized(lock) { prefs } ?: return
        }
        // Drop stale-by-clock updates: a refresh-token rotation that
        // arrives out of order shouldn't overwrite a newer snapshot.
        val current = _credentials.value
        if (creds != null && current != null && creds.issuedAt < current.issuedAt) {
            return
        }
        p.edit().apply {
            if (creds == null) {
                remove(KEY_JSON)
            } else {
                putString(KEY_JSON, json.encodeToString(Credentials.serializer(), creds))
            }
            apply()
        }
        _credentials.value = creds
    }

    fun clear() = update(null)

    private fun readFromDisk(p: SharedPreferences): Credentials? {
        val raw = p.getString(KEY_JSON, null) ?: return null
        return try {
            json.decodeFromString(Credentials.serializer(), raw)
        } catch (_: SerializationException) {
            // Corrupted or schema-incompatible blob: wipe it so the next
            // sync from the phone can land cleanly.
            p.edit().remove(KEY_JSON).apply()
            null
        }
    }
}
