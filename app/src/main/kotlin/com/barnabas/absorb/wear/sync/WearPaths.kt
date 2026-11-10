package com.barnabas.absorb.wear.sync

/**
 * Wearable Data Layer paths shared between the phone (Absorb) and the
 * watch (AbsorbWear). Keep these in lock-step with the Kotlin constants
 * in the main Absorb Android module under `com.barnabas.absorb.wear`.
 */
object WearPaths {
    /** Persisted DataItem holding the current ABS session. */
    const val AUTH = "/absorb/auth/v1"

    /** Watch -> phone message asking the phone to re-publish AUTH. Sent on
     *  cold start when the watch has no stored credentials. */
    const val AUTH_REQUEST = "/absorb/auth/request"

    /** Watch -> phone message asking the phone to wipe its DataItem (used
     *  when the user signs out from the watch). */
    const val AUTH_SIGN_OUT = "/absorb/auth/signout"

    /** Watch -> phone token pair rotated from a phone-sourced session. */
    const val AUTH_ROTATED = "/absorb/auth/rotated"

    /** Persisted DataItem holding current playback metadata + position. */
    const val PLAYER_STATE = "/absorb/player/state/v1"

    /** Watch -> phone command messages. Payload is empty for the
     *  toggle/skip variants; play-item carries a small JSON payload
     *  with itemId and optional episodeId. */
    const val PLAYER_CMD_PLAY_PAUSE = "/absorb/player/cmd/play-pause"
    const val PLAYER_CMD_SKIP_BACK = "/absorb/player/cmd/skip-back"
    const val PLAYER_CMD_SKIP_FORWARD = "/absorb/player/cmd/skip-forward"
    const val PLAYER_CMD_PLAY_ITEM = "/absorb/player/cmd/play-item"
    /** One step of the phone's media volume, from the watch's crown. */
    const val PLAYER_CMD_VOLUME_UP = "/absorb/player/cmd/volume-up"
    const val PLAYER_CMD_VOLUME_DOWN = "/absorb/player/cmd/volume-down"
    /** Asks the phone to report its media volume. */
    const val PLAYER_CMD_VOLUME_GET = "/absorb/player/cmd/volume-get"
    /** The player's extra actions, from the watch's "more" page. */
    const val PLAYER_CMD_NEXT_CHAPTER = "/absorb/player/cmd/next-chapter"
    const val PLAYER_CMD_PREV_CHAPTER = "/absorb/player/cmd/prev-chapter"
    /** Saves a bookmark at the phone's position; empty payload. */
    const val PLAYER_CMD_BOOKMARK = "/absorb/player/cmd/bookmark"
    /** JSON: one of `{"minutes":n}`, `{"chapters":n}`, `{"add":n}` (minutes)
     *  or `{"cancel":true}`. */
    const val PLAYER_CMD_SLEEP = "/absorb/player/cmd/sleep"
    /** JSON `{"positionMs":n}`, absolute in the item. */
    const val PLAYER_CMD_SEEK = "/absorb/player/cmd/seek"
    /** Stop the phone's playback and close its session. */
    const val PLAYER_CMD_STOP = "/absorb/player/cmd/stop"
    /** JSON `{"speed":x}`: the phone's playback speed. */
    const val PLAYER_CMD_SPEED = "/absorb/player/cmd/speed"
    /** Asks the phone to open the watch app through Wear OS, which may
     *  start an activity here when the watch itself may not. The phone
     *  opens `absorbwear://<applicationId>/phone` on the sending node. */
    const val PLAYER_CMD_OPEN_WATCH = "/absorb/player/cmd/open-watch"

    /** Phone -> watch message with the phone's media volume as JSON
     *  `{"index":n,"max":m}`, sent after each volume command. */
    const val PLAYER_VOLUME = "/absorb/player/volume"

    /** Persisted DataItem with the playing book's chapter list, written
     *  once per book: JSON `{"itemId":..,"chapters":[{"title","start","end"}]}`
     *  with seconds. */
    const val PLAYER_CHAPTERS = "/absorb/player/chapters/v1"

    /** Watch -> phone persisted DataItem naming the BookOrbit listening
     *  sessions the watch recorded, so the phone's stats can show them as
     *  the watch's: JSON `{"device":"..","sessions":[{"bookId":"..",
     *  "startedAt":"<ISO, ms, Z>"}]}`, oldest first, at most 100. */
    const val PLAYER_SESSIONS = "/absorb/player/sessions/v1"

    /** Prefix for all player commands — used by the phone's manifest
     *  filter so we don't have to register one entry per cmd path. */
    const val PLAYER_CMD_PREFIX = "/absorb/player/cmd"
}
