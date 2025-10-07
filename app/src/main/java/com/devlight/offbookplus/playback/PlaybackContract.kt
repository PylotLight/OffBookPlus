package com.devlight.offbookplus.playback

/**
 * A shared contract object that defines constants for communication
 * between the UI (ViewModels) and the Playback Service.
 */
object PlaybackContract {
    const val COMMAND_LOAD_MEDIA_AND_PLAY = "com.devlight.offbookplus.LOAD_MEDIA_AND_PLAY"
    const val KEY_MEDIA_ID = "media_id"
    const val KEY_MEDIA_TYPE = "media_type"
}