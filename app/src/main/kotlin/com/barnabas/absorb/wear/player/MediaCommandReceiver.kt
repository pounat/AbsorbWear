package com.barnabas.absorb.wear.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.barnabas.absorb.wear.complication.ComplicationUpdater
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives play/pause/skip actions from the cold-start now-playing
 * notification (see [NowPlayingNotifier]) and the play/pause complication,
 * and forwards them to the phone, or play/pause to the watch's own player
 * when it holds the book.
 *
 * Lives outside any activity so the buttons work even when the app's UI was
 * never opened — the PendingIntents spin this receiver up on demand.
 */
class MediaCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val commander = PlayerCommandClient(context.applicationContext)
        // goAsync() keeps the receiver alive past onReceive so the suspend
        // send can finish before the process is allowed to die.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_PLAY_PAUSE -> {
                        if (PlaybackManager.uiState.value.hasItem) {
                            withContext(Dispatchers.Main) { PlaybackManager.playPause() }
                        } else {
                            commander.playPause()
                        }
                        ComplicationUpdater.refresh()
                    }
                    ACTION_SKIP_BACK -> commander.skipBack()
                    ACTION_SKIP_FORWARD -> commander.skipForward()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.barnabas.absorb.wear.action.PLAY_PAUSE"
        const val ACTION_SKIP_BACK = "com.barnabas.absorb.wear.action.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "com.barnabas.absorb.wear.action.SKIP_FORWARD"
    }
}
