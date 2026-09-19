package tachiyomi.domain.tsuzuki.collections.execution

import java.util.concurrent.CancellationException
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

fun interface CatalogPageFetcher {
    suspend fun fetch(offset: Int, limit: Int): Result<CatalogPage>
}

object ResidualPaginator {

    suspend fun loadPage(
        residualExpression: QueryExpression?,
        logicalPageSize: Int,
        cursor: ResidualPageCursor = ResidualPageCursor(),
        maxProviderPageSize: Int? = null,
        fetcher: CatalogPageFetcher,
    ): ResidualPageResult {
        require(logicalPageSize > 0) { "Logical page size must be positive" }
        require(maxProviderPageSize == null || maxProviderPageSize > 0) {
            "Provider page size must be positive when specified"
        }

        when (val support = ResidualEvaluator.support(residualExpression)) {
            ResidualSupport.Supported -> Unit
            is ResidualSupport.Unsupported -> {
                return ResidualPageResult.UnsupportedResidual(support.reasons)
            }
        }

        val accepted = mutableListOf<tachiyomi.domain.tsuzuki.catalog.model.CatalogItem>()
        var rawOffset = cursor.rawOffset
        val seenRawPageSignatures = mutableSetOf<String>()

        while (accepted.size < logicalPageSize) {
            val remaining = logicalPageSize - accepted.size
            val requestLimit = minOf(remaining, maxProviderPageSize ?: remaining)

            val result = try {
                fetcher.fetch(rawOffset, requestLimit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return ResidualPageResult.ProviderFailure(failure)
            }

            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) {
                    throw failure
                }
                return ResidualPageResult.ProviderFailure(failure)
            }

            val page = result.getOrThrow()

            if (page.items.size > requestLimit) {
                return ResidualPageResult.PaginationInvariantFailure(
                    "Provider returned ${page.items.size} items for requested limit $requestLimit",
                )
            }

            if (page.items.isEmpty()) {
                return if (page.hasNextPage) {
                    ResidualPageResult.PaginationInvariantFailure(
                        "Provider reported hasNextPage=true without advancing raw items at offset $rawOffset",
                    )
                } else {
                    success(accepted, nextCursor = null)
                }
            }

            val signature = page.items.joinToString(separator = "|") { "${it.provider}:${it.providerId}" }
            if (!seenRawPageSignatures.add(signature)) {
                return ResidualPageResult.PaginationInvariantFailure(
                    "Provider repeated the same raw page while pagination was still in progress",
                )
            }

            for (item in page.items) {
                when (val evaluation = ResidualEvaluator.evaluate(residualExpression, item)) {
                    is ResidualEvaluation.Evaluated -> {
                        if (evaluation.truth == TruthValue.TRUE) {
                            accepted += item
                        }
                    }
                    is ResidualEvaluation.Unsupported -> {
                        return ResidualPageResult.UnsupportedResidual(evaluation.reasons)
                    }
                }
            }

            rawOffset += page.items.size

            if (page.totalCount != null && rawOffset >= page.totalCount && page.hasNextPage) {
                return ResidualPageResult.PaginationInvariantFailure(
                    "Provider reported hasNextPage=true after consuming declared totalCount=${page.totalCount}",
                )
            }

            if (accepted.size == logicalPageSize) {
                val nextCursor = if (page.hasNextPage) ResidualPageCursor(rawOffset) else null
                return success(accepted, nextCursor)
            }

            if (!page.hasNextPage) {
                return success(accepted, nextCursor = null)
            }
        }

        error("Residual pagination loop exited without returning a page")
    }

    private fun success(
        items: List<tachiyomi.domain.tsuzuki.catalog.model.CatalogItem>,
        nextCursor: ResidualPageCursor?,
    ): ResidualPageResult.Success {
        return ResidualPageResult.Success(
            LogicalCatalogPage(
                items = items.toList(),
                nextCursor = nextCursor,
            ),
        )
    }
}
