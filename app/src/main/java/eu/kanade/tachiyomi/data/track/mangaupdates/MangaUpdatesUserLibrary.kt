package eu.kanade.tachiyomi.data.track.mangaupdates

import eu.kanade.tachiyomi.data.track.model.TrackSearch

interface MangaUpdatesUserLibraryApi {
    suspend fun getUserLibrary(): MangaUpdatesUserLibrarySnapshot
}

data class MangaUpdatesUserLibrarySnapshot(
    val lists: List<MangaUpdatesUserList>,
    val entries: List<MangaUpdatesUserListEntry>,
)

data class MangaUpdatesUserList(
    val id: Long,
    val title: String,
    val type: String,
    val custom: Boolean,
)

data class MangaUpdatesUserListEntry(
    val manga: TrackSearch,
    val listId: Long,
    val progress: Double,
    val score: Double,
    val addedAt: String? = null,
)
