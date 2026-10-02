package tachiyomi.data.tsuzuki.kitsu.client

import tachiyomi.data.tsuzuki.kitsu.dto.KitsuGenreResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse

interface KitsuClient {
    suspend fun searchManga(
        query: String?,
        offset: Int,
        limit: Int,
        sort: String?,
    ): Result<KitsuMangaResponse> = searchManga(
        query = query,
        offset = offset,
        limit = limit,
        sort = sort,
        status = null,
    )

    suspend fun searchManga(
        query: String?,
        offset: Int,
        limit: Int,
        sort: String?,
        status: String?,
        genres: List<String> = emptyList(),
        subtype: String? = null,
    ): Result<KitsuMangaResponse>

    suspend fun getGenres(query: String? = null): Result<KitsuGenreResponse>

    suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse>

    suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse>

    suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse>

    suspend fun getMangaById(id: String): Result<KitsuSingleMangaResponse> = getMangaDetails(id)
}
