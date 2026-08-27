package com.devlight.offbookplus.playback

/**
 * A shared contract object that defines constants for communication
 * between the UI (ViewModels) and the Playback Service.
 */
object PlaybackContract {
    const val COMMAND_LOAD_MEDIA_AND_PLAY = "com.devlight.offbookplus.LOAD_MEDIA_AND_PLAY"
    const val COMMAND_RESUME_LAST_QUEUE = "com.devlight.offbookplus.RESUME_LAST_QUEUE"
    const val COMMAND_TOGGLE_SHUFFLE = "com.devlight.offbookplus.TOGGLE_SHUFFLE"
    const val KEY_MEDIA_ID = "media_id"
    const val KEY_MEDIA_TYPE = "media_type"

    const val EXTRA_MEDIA_TYPE = "MEDIA_TYPE"
    const val EXTRA_QUEUE_ID = "QUEUE_ID"
    const val EXTRA_SHUFFLE_ENABLED = "SHUFFLE_ENABLED"
    const val EXTRA_OPEN_PLAYER = "open_player"
}
