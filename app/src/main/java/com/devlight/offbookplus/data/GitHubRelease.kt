package com.devlight.offbookplus.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data class to parse the JSON responses from the GitHub Releases API
 * (both the `/releases/latest` endpoint and the full `/releases` list).
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList()
) {
    /** Version string without the leading 'v', e.g. "0.3.0". */
    val version: String
        get() = tagName.removePrefix("v")

    /** The installable APK for this release, or null when none is attached. */
    val apkAsset: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String
)