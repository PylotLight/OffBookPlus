package com.devlight.offbookplus.playback

/**
 * A shared contract object that defines constants for communication
 * between the UI (ViewModels) and the Playback Service.
 */
object PlaybackContract {
    const val PREFS_NAME = "PlaybackPrefs"
    const val KEY_SPEED_PREFIX = "playback_speed_"
    const val KEY_REWIND_MS = "rewind_ms"
    const val KEY_FORWARD_MS = "forward_ms"
    const val DEFAULT_REWIND_MS = 30_000L
    const val DEFAULT_FORWARD_MS = 30_000L

    const val KEY_SINGLE_TAP_ACTION = "headset_single_tap"
    const val KEY_DOUBLE_TAP_ACTION = "headset_double_tap"
    const val KEY_TRIPLE_TAP_ACTION = "headset_triple_tap"
    const val ACTION_TOGGLE = "toggle"
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_NEXT = "next"
    const val ACTION_PREVIOUS = "previous"
    const val ACTION_REWIND = "rewind"
    const val ACTION_FORWARD = "forward"
    const val ACTION_NONE = "none"
    const val DEFAULT_SINGLE_TAP_ACTION = ACTION_TOGGLE
    const val DEFAULT_DOUBLE_TAP_ACTION = ACTION_TOGGLE
    const val DEFAULT_TRIPLE_TAP_ACTION = ACTION_NEXT
    val TAP_ACTIONS = listOf(
        ACTION_TOGGLE,
        ACTION_PLAY,
        ACTION_PAUSE,
        ACTION_NEXT,
        ACTION_PREVIOUS,
        ACTION_REWIND,
        ACTION_FORWARD,
        ACTION_NONE
    )

    const val COMMAND_LOAD_MEDIA_AND_PLAY = "com.devlight.offbookplus.LOAD_MEDIA_AND_PLAY"
    const val COMMAND_RESUME_LAST_QUEUE = "com.devlight.offbookplus.RESUME_LAST_QUEUE"
    const val COMMAND_SHUFFLE_MUSIC = "com.devlight.offbookplus.SHUFFLE_MUSIC"
    const val MUSIC_QUEUE_ID = "all_music_tracks"
    const val KEY_MEDIA_ID = "media_id"
    const val KEY_MEDIA_TYPE = "media_type"

    const val EXTRA_MEDIA_TYPE = "MEDIA_TYPE"
    const val EXTRA_QUEUE_ID = "QUEUE_ID"
    const val EXTRA_SHUFFLE_ENABLED = "SHUFFLE_ENABLED"
    const val EXTRA_OPEN_PLAYER = "open_player"
}
