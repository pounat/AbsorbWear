package com.barnabas.absorb.wear.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Asks the watch face to refresh the complications when what they would
 * show changes: a different book, play or pause, or another percent
 * through. The system also polls on its own every few minutes.
 */
object ComplicationUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var requesters: List<ComplicationDataSourceUpdateRequester> = emptyList()

    private data class Key(val itemId: String?, val title: String?, val isPlaying: Boolean, val onPhone: Boolean, val percent: Int)

    /** Refreshes straight away, after a tap on the face changed things. */
    fun refresh() {
        requesters.forEach { it.requestUpdateAll() }
    }

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val app = context.applicationContext
        requesters = listOf(NowPlayingComplicationService::class.java, PlayPauseComplicationService::class.java).map {
            ComplicationDataSourceUpdateRequester.create(app, ComponentName(app, it))
        }
        scope.launch {
            combine(PlaybackManager.uiState, PlayerStateStore.state) { _, _ ->
                NowPlayingSnapshot.current()?.let {
                    Key(it.itemId, it.title, it.isPlaying, it.onPhone, (it.fraction * 100).roundToInt())
                }
            }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1_500L)
                .collect { refresh() }
        }
    }
}
