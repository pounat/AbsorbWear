package com.barnabas.absorb.wear

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.StrictMode
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.complication.ComplicationUpdater
import com.barnabas.absorb.wear.download.BookDownloads
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.sleep.SleepWatch
import com.barnabas.absorb.wear.ui.components.FaceActionStore
import com.barnabas.absorb.wear.sync.PhoneAutoOpen
import com.barnabas.absorb.wear.sync.PlayerStateClient
import com.barnabas.absorb.wear.sync.WatchSessionLog
import com.barnabas.absorb.wear.ui.PhoneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AbsorbWearApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Debug builds log anything that blocks the main thread (disk,
        // network, slow calls) with a stack trace, so jank has a name.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build(),
            )
        }
        // Opening the encrypted credential store takes over a second on a
        // watch; the splash screen waits for it instead of the main thread.
        CredentialStore.attach(this)
        PlaybackManager.init(this)
        FaceActionStore.init(this)
        PhoneAutoOpen.init(this)
        WatchSessionLog.init(this)
        SleepWatch.init(this)
        PhoneTheme.init(this)
        ComplicationUpdater.init(this)
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkGeneration.value = networkGeneration.value + 1
                    PlaybackManager.retryPendingSync()
                }
            },
        )
        appScope.launch {
            CredentialStore.init(this@AbsorbWearApp)
            BookDownloads.init(this@AbsorbWearApp)
            PlaybackManager.retryPendingSync()
        }
        // Cold start: pull the last-published player state so any surface
        // (proxy MediaSession, NowPlayingScreen) reflects reality at once
        // instead of waiting for the next ~2s push from the phone.
        appScope.launch { PlayerStateClient.seedFromPhone(this@AbsorbWearApp) }
    }

    companion object {
        /**
         * Whether [MainActivity] is currently resumed. Read from
         * `PlayerSyncService` to decide between the in-app NowPlayingScreen
         * (foreground) and the cold-start notification (background), so the
         * two control surfaces never show at once.
         */
        @Volatile
        var isInForeground: Boolean = false

        /** Bumped when Wear OS opens the app for the phone's media session,
         *  so the UI switches to the phone-remote screen. */
        val phonePlayerRequests = MutableStateFlow(0L)

        /** Bumped whenever a network becomes the default (Wi-Fi back, the
         *  phone's Bluetooth link up), so failed loads can try again. */
        val networkGeneration = MutableStateFlow(0)
    }
}
