package tachiyomi.domain.tsuzuki.collections.execution

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

class FilteredOffsetNormalizerTest {

    @Test
    fun `eligible offset never skips or duplicates when raw pages contain rejected items`() = runTest {
        val dataset = (0 until 20).map { id ->
            item(id.toString(), eligible = id % 3 != 1)
        }
        val calls = mutableListOf<Pair<Int, Int>>()
        val fetcher = RawOffsetCatalogFetcher { offset, limit ->
            calls += offset to limit
            val end = minOf(offset + limit, dataset.size)
            Result.success(
                CatalogPage(
                    items = if (offset >= dataset.size) emptyList() else dataset.subList(offset, end),
                    hasNextPage = end < dataset.size,
                    totalCount = dataset.size,
                ),
            )
        }

        val first = FilteredOffsetNormalizer.load(
            eligibleOffset = 0,
            limit = 5,
            upstreamPageSize = 4,
            fetcher = fetcher,
            include = { it.tags.contains("eligible") },
        ).getOrThrow()
        val second = FilteredOffsetNormalizer.load(
            eligibleOffset = 5,
            limit = 5,
            upstreamPageSize = 4,
            fetcher = fetcher,
            include = { it.tags.contains("eligible") },
        ).getOrThrow()

        (first.items + second.items).map { it.providerId } shouldContainExactly
            dataset.filter { it.tags.contains("eligible") }.take(10).map { it.providerId }
        first.hasNextPage shouldBe true
        second.hasNextPage shouldBe true
        calls.isNotEmpty() shouldBe true
    }

    @Test
    fun `filtered stream exhaustion returns short page and no continuation`() = runTest {
        val dataset = listOf(
            item("0", true),
            item("1", false),
            item("2", true),
        )

        val result = FilteredOffsetNormalizer.load(
            eligibleOffset = 1,
            limit = 5,
            upstreamPageSize = 2,
            fetcher = RawOffsetCatalogFetcher { offset, limit ->
                val end = minOf(offset + limit, dataset.size)
                Result.success(
                    CatalogPage(
                        items = if (offset >= dataset.size) emptyList() else dataset.subList(offset, end),
                        hasNextPage = end < dataset.size,
                        totalCount = dataset.size,
                    ),
                )
            },
            include = { it.tags.contains("eligible") },
        ).getOrThrow()

        result.items.map { it.providerId } shouldContainExactly listOf("2")
        result.hasNextPage shouldBe false
    }

    private fun item(id: String, eligible: Boolean) = CatalogItem(
        provider = "fake",
        providerId = id,
        title = id,
        tags = if (eligible) listOf("eligible") else emptyList(),
    )
}
