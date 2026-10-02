package tachiyomi.domain.tsuzuki.collections.execution

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

class PageOffsetNormalizerTest {

    @Test
    fun `arbitrary raw offset spans page sized upstream without skips`() = runTest {
        val dataset = (0 until 30).map { item(it.toString()) }
        val calls = mutableListOf<Pair<Int, Int>>()

        val result = PageOffsetNormalizer.load(
            rawOffset = 7,
            limit = 8,
            upstreamPageSize = 10,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { page, pageSize ->
                calls += page to pageSize
                val start = (page - 1) * pageSize
                val end = minOf(start + pageSize, dataset.size)
                Result.success(
                    CatalogPage(
                        items = if (start >= dataset.size) emptyList() else dataset.subList(start, end),
                        hasNextPage = end < dataset.size,
                        totalCount = dataset.size,
                    ),
                )
            },
        ).getOrThrow()

        result.items.map { it.providerId } shouldContainExactly (7 until 15).map(Int::toString)
        result.hasNextPage shouldBe true
        result.totalCount shouldBe 30
        calls shouldContainExactly listOf(1 to 10, 2 to 10)
    }

    @Test
    fun `zero based page APIs normalize the same raw slice`() = runTest {
        val dataset = (0 until 12).map { item(it.toString()) }

        val result = PageOffsetNormalizer.load(
            rawOffset = 5,
            limit = 4,
            upstreamPageSize = 6,
            pageOrigin = PageIndexOrigin.ZERO,
            fetcher = PageCatalogFetcher { page, pageSize ->
                val start = page * pageSize
                val end = minOf(start + pageSize, dataset.size)
                Result.success(
                    CatalogPage(
                        items = if (start >= dataset.size) emptyList() else dataset.subList(start, end),
                        hasNextPage = end < dataset.size,
                        totalCount = dataset.size,
                    ),
                )
            },
        ).getOrThrow()

        result.items.map { it.providerId } shouldContainExactly listOf("5", "6", "7", "8")
        result.hasNextPage shouldBe true
    }

    @Test
    fun `provider exhaustion returns short exact slice and no continuation`() = runTest {
        val dataset = (0 until 9).map { item(it.toString()) }

        val result = PageOffsetNormalizer.load(
            rawOffset = 7,
            limit = 5,
            upstreamPageSize = 4,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { page, pageSize ->
                val start = (page - 1) * pageSize
                val end = minOf(start + pageSize, dataset.size)
                Result.success(
                    CatalogPage(
                        items = if (start >= dataset.size) emptyList() else dataset.subList(start, end),
                        hasNextPage = end < dataset.size,
                        totalCount = dataset.size,
                    ),
                )
            },
        ).getOrThrow()

        result.items.map { it.providerId } shouldContainExactly listOf("7", "8")
        result.hasNextPage shouldBe false
        result.totalCount shouldBe 9
    }

    @Test
    fun `unconsumed items inside fetched upstream page keep continuation true`() = runTest {
        val result = PageOffsetNormalizer.load(
            rawOffset = 1,
            limit = 2,
            upstreamPageSize = 10,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { _, _ ->
                Result.success(
                    CatalogPage(
                        items = (0 until 10).map { item(it.toString()) },
                        hasNextPage = false,
                        totalCount = 10,
                    ),
                )
            },
        ).getOrThrow()

        result.items.map { it.providerId } shouldContainExactly listOf("1", "2")
        result.hasNextPage shouldBe true
    }

    @Test
    fun `empty page with has next fails instead of skipping unknown raw candidates`() = runTest {
        val result = PageOffsetNormalizer.load(
            rawOffset = 0,
            limit = 2,
            upstreamPageSize = 10,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { _, _ ->
                Result.success(CatalogPage(emptyList(), hasNextPage = true))
            },
        )

        result.isFailure shouldBe true
    }

    private fun item(id: String): CatalogItem = CatalogItem(
        provider = "fake",
        providerId = id,
        title = "Title $id",
    )
}
