package tachiyomi.data.tsuzuki.collections

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore

class CatalogPageCacheJsonCodecTest {

    @Test
    fun `cache payload v1 round trips complete catalog pages`() {
        val page = CatalogPage(
            items = listOf(
                CatalogItem(
                    provider = "kitsu",
                    providerId = "42",
                    title = "Title",
                    titles = mapOf("en" to "Title", "ja" to "タイトル"),
                    synopsis = "Synopsis",
                    coverUrl = "https://example/cover.jpg",
                    bannerUrl = "https://example/banner.jpg",
                    status = CatalogItemStatus.ONGOING,
                    format = CatalogItemFormat.MANGA,
                    score = CatalogScore(
                        provider = "kitsu",
                        value = 91.5,
                        maxValue = 100.0,
                        voteCount = 123,
                    ),
                    genres = listOf("Action"),
                    tags = listOf("Adventure"),
                    startDate = "2026-01-01",
                    endDate = null,
                    chapterCount = 50,
                    volumeCount = 8,
                ),
            ),
            hasNextPage = true,
            totalCount = 200,
        )

        CatalogPageCacheJsonCodec.decode(
            CatalogPageCacheJsonCodec.encode(page),
        ) shouldBe page
    }

    @Test
    fun `unknown cache payload schema version fails closed`() {
        val page = CatalogPage(
            items = emptyList(),
            hasNextPage = false,
            totalCount = 0,
        )
        val encoded = CatalogPageCacheJsonCodec.encode(page)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        shouldThrow<IllegalArgumentException> {
            CatalogPageCacheJsonCodec.decode(encoded)
        }
    }
}
