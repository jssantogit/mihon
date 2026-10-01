package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.hikka.HikkaIntegrationApi
import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.RatingsProvider

class HikkaIntegrationProviderTest {

    @Test
    fun `hikka contributes only its native community rating`() = runTest {
        val provider = HikkaIntegrationProvider.forTest(
            FakeHikkaIntegrationApi(
                searchResults = listOf(
                    manga(
                        slug = "dungeon-seeker-ac93e6",
                        title = "Dungeon Seeker",
                        year = 2016,
                        nativeScore = 7.39,
                        nativeVotes = 13,
                        importedScore = 6.53,
                        importedVotes = 16_301,
                    ),
                ),
            ),
        )

        (provider as Any is RatingsProvider) shouldBe true

        val match = (provider as RatingsProvider).ratingFor(
            CatalogItem(
                provider = "mal",
                providerId = "98820",
                title = "Dungeon Seeker",
                startDate = "2016",
            ),
        ).getOrThrow()

        match?.rating?.providerId shouldBe "hikka"
        match?.rating?.value shouldBe 7.39
        match?.rating?.scaleMax shouldBe 10.0
        match?.verifiedIdentity shouldBe false
    }

    @Test
    fun `hikka omits titles without a native community score`() = runTest {
        val provider = HikkaIntegrationProvider.forTest(
            FakeHikkaIntegrationApi(
                detailsBySlug = mapOf(
                    "example" to manga(
                        slug = "example",
                        title = "Example",
                        year = 2024,
                        nativeScore = 0.0,
                        nativeVotes = 0,
                        importedScore = 8.5,
                        importedVotes = 1_000,
                    ),
                ),
            ),
        )

        (provider as RatingsProvider).ratings("example").getOrThrow() shouldBe emptyList()
    }

    private class FakeHikkaIntegrationApi(
        private val searchResults: List<HKManga> = emptyList(),
        private val detailsBySlug: Map<String, HKManga> = emptyMap(),
    ) : HikkaIntegrationApi {
        override suspend fun searchPublic(query: String): List<HKManga> = searchResults

        override suspend fun getMangaDetailsPublic(slug: String): HKManga? = detailsBySlug[slug]
    }

    private fun manga(
        slug: String,
        title: String,
        year: Int,
        nativeScore: Double,
        nativeVotes: Int,
        importedScore: Double,
        importedVotes: Int,
    ): HKManga =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            """
            {
              "data_type": "manga",
              "title_original": "$title",
              "media_type": "manga",
              "translated_ua": false,
              "status": "finished",
              "image": "https://example.invalid/cover.jpg",
              "year": $year,
              "native_scored_by": $nativeVotes,
              "native_score": $nativeScore,
              "scored_by": $importedVotes,
              "score": $importedScore,
              "slug": "$slug"
            }
            """.trimIndent(),
        )
}
