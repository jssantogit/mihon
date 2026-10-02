package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import java.util.concurrent.CancellationException

fun interface RawOffsetCatalogFetcher {
    suspend fun fetch(offset: Int, limit: Int): Result<CatalogPage>
}

object FilteredOffsetNormalizer {

    suspend fun load(
        eligibleOffset: Int,
        limit: Int,
        upstreamPageSize: Int,
        fetcher: RawOffsetCatalogFetcher,
        include: (tachiyomi.domain.tsuzuki.catalog.model.CatalogItem) -> Boolean,
    ): Result<CatalogPage> {
        require(eligibleOffset >= 0) { "Eligible offset must be non-negative" }
        require(limit > 0) { "Eligible limit must be positive" }
        require(upstreamPageSize > 0) { "Upstream page size must be positive" }

        val targetEligibleCount = eligibleOffset + limit + 1
        val eligible = mutableListOf<tachiyomi.domain.tsuzuki.catalog.model.CatalogItem>()
        var rawOffset = 0
        var exhausted = false
        var declaredTotal: Int? = null

        while (eligible.size < targetEligibleCount && !exhausted) {
            val result = try {
                fetcher.fetch(rawOffset, upstreamPageSize)
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
                        "Provider returned ${page.items.size} raw items for requested page size $upstreamPageSize",
                    ),
                )
            }
            if (page.items.isEmpty()) {
                if (page.hasNextPage) {
                    return Result.failure(
                        IllegalStateException(
                            "Provider reported hasNextPage=true without advancing raw items at offset $rawOffset",
                        ),
                    )
                }
                exhausted = true
                break
            }

            if (declaredTotal == null) {
                declaredTotal = page.totalCount
            } else if (page.totalCount != null && page.totalCount != declaredTotal) {
                return Result.failure(
                    IllegalStateException(
                        "Provider totalCount changed during filtered paging: $declaredTotal -> ${page.totalCount}",
                    ),
                )
            }

            eligible += page.items.filter(include)
            rawOffset += page.items.size

            if (page.totalCount != null && rawOffset >= page.totalCount && page.hasNextPage) {
                return Result.failure(
                    IllegalStateException(
                        "Provider reported hasNextPage=true after consuming declared totalCount=${page.totalCount}",
                    ),
                )
            }
            exhausted = !page.hasNextPage
        }

        val pageItems = eligible.drop(eligibleOffset).take(limit)
        val hasNextPage = eligible.size > eligibleOffset + pageItems.size || !exhausted
        val eligibleTotal = if (exhausted) eligible.size else null

        return Result.success(
            CatalogPage(
                items = pageItems,
                hasNextPage = hasNextPage,
                totalCount = eligibleTotal,
            ),
        )
    }
}
