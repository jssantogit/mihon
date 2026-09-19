package tachiyomi.domain.tsuzuki.collections.execution

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
import java.util.concurrent.CancellationException

class ResidualPaginatorTest {

    private val over100 = QueryExpression.Predicate(
        field = QueryField.CHAPTER_COUNT,
        operator = QueryOperator.GREATER_THAN,
        value = QueryValue.of(100),
    )

    @Test
    fun `one raw page can fill one logical page`() = runTest {
        val fetcher = datasetFetcher(
            item("1", 101),
            item("2", 102),
            item("3", 103),
        )

        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = fetcher,
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("1", "2")
        result.page.nextCursor shouldBe ResidualPageCursor(rawOffset = 2)
        result.page.hasNextPage shouldBe true
    }

    @Test
    fun `paginator refills from page two when first raw page filters down`() = runTest {
        val calls = mutableListOf<Pair<Int, Int>>()
        val fetcher = datasetFetcher(
            item("1", 10),
            item("2", 101),
            item("3", 20),
            item("4", 102),
            onFetch = { offset, limit -> calls += offset to limit },
        )

        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = fetcher,
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("2", "4")
        calls shouldContainExactly listOf(0 to 2, 2 to 1, 3 to 1)
        result.page.nextCursor shouldBe null
    }

    @Test
    fun `residual filtering can span at least three provider pages`() = runTest {
        var calls = 0
        val fetcher = datasetFetcher(
            item("1", 1),
            item("2", 2),
            item("3", 3),
            item("4", 4),
            item("5", 101),
            item("6", 102),
            onFetch = { _, _ -> calls++ },
        )

        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = fetcher,
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("5", "6")
        calls shouldBe 3
        result.page.nextCursor shouldBe null
    }

    @Test
    fun `provider exhaustion returns accepted subset without inventing next page`() = runTest {
        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 3,
            fetcher = datasetFetcher(
                item("1", 101),
                item("2", 2),
                item("3", 3),
            ),
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("1")
        result.page.nextCursor shouldBe null
        result.page.hasNextPage shouldBe false
    }

    @Test
    fun `cursor advances by raw items consumed rather than accepted count`() = runTest {
        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = datasetFetcher(
                item("1", 101),
                item("2", 2),
                item("3", 102),
                item("4", 4),
                item("5", 103),
            ),
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("1", "3")
        result.page.nextCursor shouldBe ResidualPageCursor(rawOffset = 3)
    }

    @Test
    fun `consecutive logical pages do not skip raw provider items`() = runTest {
        val fetcher = datasetFetcher(
            item("1", 101),
            item("2", 2),
            item("3", 102),
            item("4", 4),
            item("5", 103),
            item("6", 104),
        )

        val first = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = fetcher,
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        first.page.items.map { it.providerId } shouldContainExactly listOf("1", "3")
        first.page.nextCursor shouldBe ResidualPageCursor(3)

        val second = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            cursor = first.page.nextCursor!!,
            fetcher = fetcher,
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        second.page.items.map { it.providerId } shouldContainExactly listOf("5", "6")
        second.page.nextCursor shouldBe null
    }

    @Test
    fun `empty provider page with hasNext true fails instead of looping`() = runTest {
        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = CatalogPageFetcher { _, _ ->
                Result.success(CatalogPage(items = emptyList(), hasNextPage = true))
            },
        )

        result.shouldBeInstanceOf<ResidualPageResult.PaginationInvariantFailure>()
    }

    @Test
    fun `repeated provider page fails as no progress anomaly`() = runTest {
        val repeated = item("same", 1)

        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = CatalogPageFetcher { _, _ ->
                Result.success(CatalogPage(items = listOf(repeated), hasNextPage = true))
            },
        )

        result.shouldBeInstanceOf<ResidualPageResult.PaginationInvariantFailure>()
    }

    @Test
    fun `provider returning more than requested fails closed`() = runTest {
        val result = ResidualPaginator.loadPage(
            residualExpression = null,
            logicalPageSize = 1,
            fetcher = CatalogPageFetcher { _, _ ->
                Result.success(
                    CatalogPage(
                        items = listOf(item("1", 1), item("2", 2)),
                        hasNextPage = false,
                    ),
                )
            },
        )

        result.shouldBeInstanceOf<ResidualPageResult.PaginationInvariantFailure>()
    }

    @Test
    fun `provider failure propagates as typed failure`() = runTest {
        val failure = IllegalStateException("provider exploded")

        val result = ResidualPaginator.loadPage(
            residualExpression = over100,
            logicalPageSize = 2,
            fetcher = CatalogPageFetcher { _, _ -> Result.failure(failure) },
        ).shouldBeInstanceOf<ResidualPageResult.ProviderFailure>()

        result.cause shouldBe failure
    }

    @Test
    fun `cancellation from provider result is rethrown`() = runTest {
        val cancellation = CancellationException("cancelled")
        var caught: CancellationException? = null

        try {
            ResidualPaginator.loadPage(
                residualExpression = over100,
                logicalPageSize = 2,
                fetcher = CatalogPageFetcher { _, _ -> Result.failure(cancellation) },
            )
        } catch (error: CancellationException) {
            caught = error
        }

        caught shouldBe cancellation
    }

    @Test
    fun `thrown cancellation is rethrown`() = runTest {
        val cancellation = CancellationException("cancelled")
        var caught: CancellationException? = null

        try {
            ResidualPaginator.loadPage(
                residualExpression = over100,
                logicalPageSize = 2,
                fetcher = CatalogPageFetcher { _, _ -> throw cancellation },
            )
        } catch (error: CancellationException) {
            caught = error
        }

        caught shouldBe cancellation
    }

    @Test
    fun `unsupported residual semantics fail before provider fetch`() = runTest {
        var calls = 0
        val unsupported = QueryExpression.Predicate(
            field = QueryField.AUTHOR,
            operator = QueryOperator.EQUALS,
            value = QueryValue.of("Author"),
        )

        val result = ResidualPaginator.loadPage(
            residualExpression = unsupported,
            logicalPageSize = 2,
            fetcher = CatalogPageFetcher { _, _ ->
                calls++
                Result.success(CatalogPage(emptyList(), hasNextPage = false))
            },
        )

        result.shouldBeInstanceOf<ResidualPageResult.UnsupportedResidual>()
        calls shouldBe 0
    }

    @Test
    fun `not chapter count over 100 never accepts missing chapter count`() = runTest {
        val expression = QueryExpression.Not(over100)

        val result = ResidualPaginator.loadPage(
            residualExpression = expression,
            logicalPageSize = 1,
            fetcher = datasetFetcher(item("missing", chapterCount = null)),
        ).shouldBeInstanceOf<ResidualPageResult.Success>()

        result.page.items shouldBe emptyList()
    }

    private fun datasetFetcher(
        vararg items: CatalogItem,
        onFetch: (Int, Int) -> Unit = { _, _ -> },
    ): CatalogPageFetcher {
        val dataset = items.toList()
        return CatalogPageFetcher { offset, limit ->
            onFetch(offset, limit)
            val end = minOf(offset + limit, dataset.size)
            val pageItems = if (offset >= dataset.size) emptyList() else dataset.subList(offset, end)
            Result.success(
                CatalogPage(
                    items = pageItems,
                    hasNextPage = end < dataset.size,
                    totalCount = dataset.size,
                ),
            )
        }
    }

    private fun item(
        providerId: String,
        chapterCount: Int?,
    ): CatalogItem = CatalogItem(
        provider = "fake",
        providerId = providerId,
        title = "Title $providerId",
        chapterCount = chapterCount,
    )
}
