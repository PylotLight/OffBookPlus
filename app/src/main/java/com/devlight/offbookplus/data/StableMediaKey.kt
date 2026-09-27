package com.devlight.offbookplus.data

import com.devlight.offbookplus.model.MediaType
import java.net.URLDecoder

object StableMediaKey {
    fun fileName(mediaId: String): String {
        val decoded = runCatching { URLDecoder.decode(mediaId, "UTF-8") }.getOrNull() ?: mediaId
        return decoded.substringAfterLast('/').lowercase()
    }

    fun matches(a: String, b: String): Boolean {
        if (a == b) return true
        val fa = fileName(a)
        return fa.isNotEmpty() && fa == fileName(b)
    }

    fun findTrackMatch(wantedId: String, rows: List<TrackProgressEntity>): TrackProgressEntity? {
        rows.firstOrNull { it.mediaId == wantedId }?.let { return it }
        val name = fileName(wantedId)
        if (name.isEmpty()) return null
        return rows.firstOrNull { it.mediaType != MediaType.MUSIC && fileName(it.mediaId) == name }
            ?: rows.firstOrNull { fileName(it.mediaId) == name }
    }

    fun remapQueueIds(savedIds: List<String>, validIds: Set<String>): List<String> {
        if (savedIds.all { it in validIds }) return savedIds
        val byFileName = validIds.groupBy(::fileName).mapValues { it.value.first() }
        return savedIds.mapNotNull { id ->
            if (id in validIds) id else byFileName[fileName(id)]
        }
    }

    fun remapToEntities(
        savedIds: List<String>,
        entitiesById: Map<String, MediaItemEntity>,
        allValid: List<MediaItemEntity>
    ): List<MediaItemEntity> {
        if (savedIds.all { entitiesById.containsKey(it) }) {
            return savedIds.mapNotNull { entitiesById[it] }
        }
        val byFileName = allValid.groupBy { fileName(it.id) }.mapValues { it.value.first() }
        return savedIds.mapNotNull { id ->
            entitiesById[id] ?: byFileName[fileName(id)]
        }
    }
}
