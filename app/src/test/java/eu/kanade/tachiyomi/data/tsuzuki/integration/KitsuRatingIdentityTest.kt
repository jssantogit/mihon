package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError

class KitsuRatingIdentityTest {

    @Test
    fun `kitsu rating resolves from a verified MAL identity`() = runTest {
        val client = FakeKitsuClient()
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(client),
        )

        val ratings = provider.ratingsFor(
            mapOf("mal" to "13"),
        ).getOrThrow()

        ratings.map { it.providerId } shouldContainExactly listOf("kitsu")
        ratings.map { it.value } shouldContainExactly listOf(85.08)
    }

    private class FakeKitsuClient : KitsuClient {
        override suspend fun searchManga(
            query: String?,
            offset: Int,
            limit: Int,
            sort: String?,
            status: String?,
        ): Result<KitsuMangaResponse> = Result.success(KitsuMangaResponse())

        override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse> =
            if (kitsuId == "12") {
                Result.success(
                    KitsuSingleMangaResponse(
                        data = KitsuMangaResource(
                            id = "12",
                            type = "manga",
                            attributes = KitsuMangaAttributes(
                                canonicalTitle = "One Piece",
                                averageRating = "85.08",
                            ),
                        ),
                    ),
                )
            } else {
                Result.failure(CatalogError.ItemNotFound(kitsuId))
            }

        override suspend fun getMangaIdByMalId(malId: String): Result<String?> =
            Result.success(if (malId == "13") "12" else null)
    }
}
