package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.data.track.model.TrackSearch

interface BangumiUserLibraryApi {
    suspend fun getUserLibrary(): List<BangumiUserListEntry>
}

data class BangumiUserListEntry(
    val manga: TrackSearch,
    val collectionType: Int,
    val progress: Double,
    val score: Double,
    val updatedAt: String? = null,
)
