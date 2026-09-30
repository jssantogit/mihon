package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuManga
import kotlinx.serialization.Serializable

interface KitsuUserLibraryApi {
    suspend fun getUserMangaList(): List<KitsuUserListEntry>
}

data class KitsuUserListEntry(
    val manga: KitsuManga,
    val status: String,
    val progress: Double,
    val score: Double,
    val updatedAt: String? = null,
)

@Serializable
data class KitsuUserLibraryResult(
    val data: KitsuUserLibraryRoot,
)

@Serializable
data class KitsuUserLibraryRoot(
    val currentAccount: KitsuUserLibraryAccount?,
)

@Serializable
data class KitsuUserLibraryAccount(
    val profile: KitsuUserLibraryProfile,
)

@Serializable
data class KitsuUserLibraryProfile(
    val library: KitsuUserLibrary,
)

@Serializable
data class KitsuUserLibrary(
    val all: KitsuUserLibraryConnection,
)

@Serializable
data class KitsuUserLibraryConnection(
    val pageInfo: KitsuUserLibraryPageInfo,
    val nodes: List<KitsuUserLibraryNode>,
)

@Serializable
data class KitsuUserLibraryPageInfo(
    val endCursor: String?,
    val hasNextPage: Boolean,
)

@Serializable
data class KitsuUserLibraryNode(
    val id: String,
    val status: String,
    val progress: Long,
    val rating: Long?,
    val updatedAt: String?,
    val media: KitsuManga,
)
