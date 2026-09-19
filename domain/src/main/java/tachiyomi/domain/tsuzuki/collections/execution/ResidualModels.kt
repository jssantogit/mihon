package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem

enum class TruthValue {
    TRUE,
    FALSE,
    UNKNOWN,
    ;

    fun not(): TruthValue = when (this) {
        TRUE -> FALSE
        FALSE -> TRUE
        UNKNOWN -> UNKNOWN
    }

    companion object {
        fun all(values: Iterable<TruthValue>): TruthValue {
            var sawUnknown = false
            for (value in values) {
                when (value) {
                    FALSE -> return FALSE
                    UNKNOWN -> sawUnknown = true
                    TRUE -> Unit
                }
            }
            return if (sawUnknown) UNKNOWN else TRUE
        }

        fun any(values: Iterable<TruthValue>): TruthValue {
            var sawUnknown = false
            for (value in values) {
                when (value) {
                    TRUE -> return TRUE
                    UNKNOWN -> sawUnknown = true
                    FALSE -> Unit
                }
            }
            return if (sawUnknown) UNKNOWN else FALSE
        }
    }
}

sealed interface ResidualSupport {
    data object Supported : ResidualSupport

    data class Unsupported(
        val reasons: List<String>,
    ) : ResidualSupport
}

sealed interface ResidualEvaluation {
    data class Evaluated(
        val truth: TruthValue,
    ) : ResidualEvaluation

    data class Unsupported(
        val reasons: List<String>,
    ) : ResidualEvaluation
}

data class ResidualPageCursor(
    val rawOffset: Int = 0,
) {
    init {
        require(rawOffset >= 0) { "Residual raw offset must be non-negative" }
    }
}

data class LogicalCatalogPage(
    val items: List<CatalogItem>,
    val nextCursor: ResidualPageCursor?,
) {
    val hasNextPage: Boolean
        get() = nextCursor != null
}

sealed interface ResidualPageResult {
    data class Success(
        val page: LogicalCatalogPage,
    ) : ResidualPageResult

    data class UnsupportedResidual(
        val reasons: List<String>,
    ) : ResidualPageResult

    data class ProviderFailure(
        val cause: Throwable,
    ) : ResidualPageResult

    data class PaginationInvariantFailure(
        val reason: String,
    ) : ResidualPageResult
}
