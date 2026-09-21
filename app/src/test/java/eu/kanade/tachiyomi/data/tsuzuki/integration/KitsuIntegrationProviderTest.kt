package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider

class KitsuIntegrationProviderTest {

    @Test
    fun `kitsu search exposes chapter count but does not create chapter evidence`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(
                        KitsuMangaResponse(
                            data = listOf(
                                KitsuMangaResource(
                                    id = "kitsu-1",
                                    type = "manga",
                                    attributes = KitsuMangaAttributes(
                                        canonicalTitle = "Dandadan",
                                        chapterCount = 205,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val page = provider.search(CatalogQuery(query = "Dandadan")).getOrThrow()

        page.items.single().chapterCount shouldBe 205
        (provider as Any is ChapterEvidenceProvider) shouldBe false
    }

    private class FakeKitsuClient(
        private val searchResult: Result<KitsuMangaResponse>,
    ) : KitsuClient {

        override suspend fun searchManga(
            query: String?,
            offset: Int,
            limit: Int,
            sort: String?,
            status: String?,
        ): Result<KitsuMangaResponse> = searchResult

        override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse> =
            Result.failure(CatalogError.ItemNotFound(kitsuId))
    }
}
