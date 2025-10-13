package com.devlight.offbookplus.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal data class to parse the JSON response from the GitHub Releases /latest endpoint.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<ReleaseAsset>
)

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String
)