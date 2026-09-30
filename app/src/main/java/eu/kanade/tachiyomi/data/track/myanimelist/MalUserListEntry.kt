package eu.kanade.tachiyomi.data.track.myanimelist

import eu.kanade.tachiyomi.data.track.model.TrackSearch

data class MalUserListEntry(
    val manga: TrackSearch,
    val status: String,
    val progress: Double,
    val score: Double,
    val updatedAt: String? = null,
)
