package com.devlight.offbookplus.model

/**
 * Defines the different types of media supported by the app.
 *
 * @param title The display name for the media type.
 * @param directoryName The expected folder name on the external storage (e.g., 'Audiobooks').
 */
enum class MediaType(val title: String, val directoryName: String) {
    AUDIOBOOKS("Audiobooks", "Audiobooks"),
    PODCASTS("Podcasts", "Podcasts"),
    MUSIC("Music", "Music")
}