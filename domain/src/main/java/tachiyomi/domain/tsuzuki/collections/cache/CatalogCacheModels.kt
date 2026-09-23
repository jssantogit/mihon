package tachiyomi.domain.tsuzuki.collections.cache

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

data class CatalogCacheKey(
    val providerId: String,
    val normalizedQueryKey: String,
    val sort: CatalogSort,
    val rawOffset: Int,
    val pageSize: Int,
) {
    init {
        require(providerId.isNotBlank()) { "Cache providerId cannot be blank" }
        require(normalizedQueryKey.isNotBlank()) { "Cache query key cannot be blank" }
        require(rawOffset >= 0) { "Cache rawOffset cannot be negative" }
        require(pageSize > 0) { "Cache pageSize must be positive" }
    }

    companion object {
        const val NO_QUERY_KEY: String = "<no-query>"

        fun fromExpression(
            providerId: String,
            expression: QueryExpression?,
            sort: CatalogSort,
            rawOffset: Int,
            pageSize: Int,
        ): CatalogCacheKey = CatalogCacheKey(
            providerId = providerId,
            normalizedQueryKey = expression?.normalizedKey() ?: NO_QUERY_KEY,
            sort = sort,
            rawOffset = rawOffset,
            pageSize = pageSize,
        )
    }
}

enum class CacheFreshness {
    FRESH,
    STALE,
}

sealed interface CatalogCacheLookup {
    data object Miss : CatalogCacheLookup

    data class Hit(
        val page: CatalogPage,
        val freshness: CacheFreshness,
        val fetchedAt: Long,
    ) : CatalogCacheLookup
}

fun classifyCacheWindow(
    now: Long,
    expiresAt: Long,
    staleUntil: Long,
): CacheFreshness? = when {
    now <= expiresAt -> CacheFreshness.FRESH
    now <= staleUntil -> CacheFreshness.STALE
    else -> null
}

fun safeCacheDeadline(
    base: Long,
    duration: Long,
): Long {
    require(duration >= 0) { "Cache duration cannot be negative" }
    return if (Long.MAX_VALUE - base < duration) Long.MAX_VALUE else base + duration
}
