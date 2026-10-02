package tachiyomi.data.tsuzuki.collections

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

class CatalogPageCacheJsonCodecTest {

    @Test
    fun `cache round trip preserves metadata required by residual evaluation`() {
        val page = CatalogPage(
            items = listOf(
                CatalogItem(
                    provider = "fake",
                    providerId = "42",
                    title = "Cached",
                    authors = listOf("Author"),
                    artists = listOf("Artist"),
                    genres = listOf("Action"),
                    tags = listOf("Award"),
                    publishers = listOf("Publisher"),
                    magazines = listOf("Magazine"),
                    categories = listOf("Category"),
                    demographics = listOf("Shounen"),
                    country = "JP",
                    popularity = 1234,
                    favorites = 321,
                    rank = 12.5,
                    startDate = "2022-01-02",
                    endDate = "2024-03-04",
                    chapterCount = 100,
                    volumeCount = 10,
                ),
            ),
            hasNextPage = true,
            totalCount = 99,
        )

        CatalogPageCacheJsonCodec.decode(
            CatalogPageCacheJsonCodec.encode(page),
        ) shouldBe page
    }
}
