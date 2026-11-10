package com.barnabas.absorb.wear.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.playback.PlaybackManager.PlayTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player's extra actions: one sits on the face as a small chip under
 * the transport, the rest on the page below it. A long press on any of
 * them there swaps it onto the face.
 */
enum class FaceAction(val title: String) {
    SPEED("Playback speed"),
    BOOKMARK("Bookmark"),
    SLEEP("Sleep timer"),
    CHAPTERS("Chapters"),
    BOOKMARKS("Bookmarks"),
    DETAILS("Details"),
    PLAY_ON("Play on"),
    STOP("Stop"),
}

/** Which action sits on the face; kept across launches. */
object FaceActionStore {
    private const val PREFS = "absorb_wear_playback"
    private const val KEY = "face_action"
    private var prefs: SharedPreferences? = null
    private val _action = MutableStateFlow(FaceAction.SPEED)
    val action: StateFlow<FaceAction> = _action.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY, null)
        _action.value = FaceAction.entries.firstOrNull { it.name == saved } ?: FaceAction.SPEED
    }

    fun set(action: FaceAction) {
        _action.value = action
        prefs?.edit()?.putString(KEY, action.name)?.apply()
    }
}

/** What the extra actions do, for the face chip and the more page alike. */
class PlayerActions(
    val speedLabel: String,
    val onSpeed: () -> Unit,
    /** How the timer reads on the page; null when off. */
    val sleepLabel: String?,
    /** The timer's short form for the face while it runs; null when off. */
    val sleepChip: String?,
    /** Opens the timer's choices. */
    val onSleep: () -> Unit,
    /** A tap on the running timer: more time, or the choices. */
    val onSleepTap: () -> Unit,
    val playTarget: PlayTarget,
    val onPlayOn: () -> Unit,
    val onPrevChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onBookmark: suspend () -> Boolean,
    val onChapters: () -> Unit,
    val onBookmarks: () -> Unit,
    val onDetails: () -> Unit,
    val onStop: () -> Unit,
)

private class ChipSpec(val label: String, val icon: ImageVector?, val enabled: Boolean, val onClick: () -> Unit)

/** The action on the face, as a small chip under the transport. */
@Composable
fun FaceActionChip(action: FaceAction, actions: PlayerActions, enabled: Boolean, hasChapters: Boolean) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) {
        if (note == null) return@LaunchedEffect
        delay(2_000)
        note = null
    }
    val phone = actions.playTarget == PlayTarget.PHONE
    val spec = when (action) {
        FaceAction.SPEED -> ChipSpec(actions.speedLabel, null, enabled, actions.onSpeed)
        FaceAction.BOOKMARK -> ChipSpec(
            note ?: "Bookmark",
            if (note == "Saved") Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
            enabled && note == null,
        ) { scope.launch { note = if (actions.onBookmark()) "Saved" else "Not saved" } }
        FaceAction.SLEEP -> ChipSpec(
            actions.sleepChip ?: "Sleep",
            Icons.Rounded.Bedtime,
            enabled,
            if (actions.sleepChip != null) actions.onSleepTap else actions.onSleep,
        )
        FaceAction.CHAPTERS -> ChipSpec("Chapters", Icons.Rounded.FormatListNumbered, enabled && hasChapters, actions.onChapters)
        FaceAction.BOOKMARKS -> ChipSpec("Bookmarks", Icons.Rounded.Bookmarks, enabled, actions.onBookmarks)
        FaceAction.DETAILS -> ChipSpec("Details", Icons.Rounded.Info, enabled, actions.onDetails)
        FaceAction.PLAY_ON -> ChipSpec(
            if (phone) "Phone" else "Watch",
            if (phone) Icons.Rounded.Smartphone else Icons.Rounded.Watch,
            true,
            actions.onPlayOn,
        )
        FaceAction.STOP -> ChipSpec("Stop", Icons.Rounded.Stop, enabled, actions.onStop)
    }
    CompactChip(
        onClick = spec.onClick,
        enabled = spec.enabled,
        icon = spec.icon?.let { icon ->
            { Icon(icon, contentDescription = action.title, modifier = Modifier.size(ChipDefaults.SmallIconSize)) }
        },
        label = { Text(spec.label, maxLines = 1, softWrap = false) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

/**
 * A long press, watched ahead of the chip's own click so the click does
 * not fire with it. A finger that moves is a scroll, not a press.
 */
fun Modifier.onLongPress(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                if (!change.pressed || (change.position - down.position).getDistance() > slop) return@withTimeoutOrNull
            }
        }
        if (released != null) return@awaitEachGesture
        action()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
