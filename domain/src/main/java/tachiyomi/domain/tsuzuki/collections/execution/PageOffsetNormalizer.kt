package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import java.util.concurrent.CancellationException

enum class PageIndexOrigin {
    ZERO,
    ONE,
}

fun interface PageCatalogFetcher {
    suspend fun fetch(page: Int, pageSize: Int): Result<CatalogPage>
}

object PageOffsetNormalizer {

    suspend fun load(
        rawOffset: Int,
        limit: Int,
        upstreamPageSize: Int,
        pageOrigin: PageIndexOrigin,
        fetcher: PageCatalogFetcher,
    ): Result<CatalogPage> {
        require(rawOffset >= 0) { "Raw offset must be non-negative" }
        require(limit > 0) { "Raw limit must be positive" }
        require(upstreamPageSize > 0) { "Upstream page size must be positive" }

        val firstPageZeroBased = rawOffset / upstreamPageSize
        var pageZeroBased = firstPageZeroBased
        var skipInFirstPage = rawOffset % upstreamPageSize
        val accepted = mutableListOf<tachiyomi.domain.tsuzuki.catalog.model.CatalogItem>()
        var declaredTotal: Int? = null
        var providerHasMore = true
        var unconsumedInLastFetchedPage = false

        while (accepted.size < limit && providerHasMore) {
            val upstreamPage = when (pageOrigin) {
                PageIndexOrigin.ZERO -> pageZeroBased
                PageIndexOrigin.ONE -> pageZeroBased + 1
            }
            val result = try {
                fetcher.fetch(upstreamPage, upstreamPageSize)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return Result.failure(failure)
            }

            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                return Result.failure(failure)
            }

            val page = result.getOrThrow()
            if (page.items.size > upstreamPageSize) {
                return Result.failure(
                    IllegalStateException(
                        "Provider returned \${page.items.size} items for upstream page size \$upstreamPageSize",
                    ),
                )
            }
            if (page.items.isEmpty() && page.hasNextPage) {
                return Result.failure(
                    IllegalStateException(
                        "Provider reported hasNextPage=true for an empty upstream page \$upstreamPage",
                    ),
                )
            }

            if (declaredTotal == null) {
                declaredTotal = page.totalCount
            } else if (page.totalCount != null && declaredTotal != page.totalCount) {
                return Result.failure(
                    IllegalStateException(
                        "Provider totalCount changed during normalized paging: \$declaredTotal -> \${page.totalCount}",
                    ),
                )
            }

            if (skipInFirstPage > page.items.size) {
                return Result.failure(
                    IllegalStateException(
                        "Raw offset \$rawOffset exceeds items available in upstream page \$upstreamPage",
                    ),
                )
            }

            val available = page.items.drop(skipInFirstPage)
            skipInFirstPage = 0
            val remaining = limit - accepted.size
            accepted += available.take(remaining)
            unconsumedInLastFetchedPage = available.size > remaining
            providerHasMore = page.hasNextPage

            if (accepted.size >= limit || unconsumedInLastFetchedPage) break
            if (!providerHasMore) break
            pageZeroBased += 1
        }

        val consumedThrough = rawOffset + accepted.size
        val hasNext = when {
            unconsumedInLastFetchedPage -> true
            declaredTotal != null -> consumedThrough < declaredTotal!!
            else -> providerHasMore
        }

        return Result.success(
            CatalogPage(
                items = accepted.toList(),
                hasNextPage = hasNext,
                totalCount = declaredTotal,
            ),
        )
    }
}
