package com.barnabas.absorb.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.player.NowPlayingNotifier
import com.barnabas.absorb.wear.ui.AbsorbApp

class MainActivity : ComponentActivity() {

    // Needed so the cold-start now-playing notification can actually post on
    // Android 13+ (Wear OS 4+). Requested once on first open — by the time
    // the user has gone through setup, future cold starts can surface.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run BEFORE super.onCreate so the splash hand-off happens at
        // the right point in the activity lifecycle.
        installSplashScreen().setKeepOnScreenCondition { !CredentialStore.ready.value }
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        handleIntent(intent)
        setContent {
            // Plain Box root — Wear Compose 1.4's Scaffold doesn't add value
            // for a one-screen MVP, and AppScaffold lives in newer
            // material3 (1.5+) so it'd force a dependency bump.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AbsorbApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Wear OS opened us for the phone's media session, or the phone opened
     *  us as it started playing: show its controls. */
    private fun handleIntent(intent: Intent?) {
        val fromPhone = intent?.action == Intent.ACTION_VIEW &&
            intent.data?.scheme == PHONE_LINK_SCHEME && intent.data?.path == PHONE_LINK_PATH
        if (intent?.action == ACTION_REMOTE_MEDIA_ACTIVITY || fromPhone) {
            AbsorbWearApp.phonePlayerRequests.value = System.currentTimeMillis()
        }
    }

    override fun onResume() {
        super.onResume()
        // App is on screen: NowPlayingScreen is the control surface, so drop
        // the cold-start notification to avoid showing both at once.
        AbsorbWearApp.isInForeground = true
        NowPlayingNotifier.cancel(this)
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded: hand control back to the notification path. The next
        // phone state push (via PlayerSyncService) re-posts it.
        AbsorbWearApp.isInForeground = false
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** com.google.wear.services.media.MediaManager.ACTION_REMOTE_MEDIA_ACTIVITY */
        const val ACTION_REMOTE_MEDIA_ACTIVITY = "com.google.wear.services.media.action.REMOTE_MEDIA_ACTIVITY"

        /** `absorbwear://<applicationId>/phone`, opened by the phone; see
         *  [com.barnabas.absorb.wear.sync.PhoneAutoOpen]. */
        const val PHONE_LINK_SCHEME = "absorbwear"
        const val PHONE_LINK_PATH = "/phone"
    }
}
