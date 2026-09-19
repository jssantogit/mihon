package tachiyomi.domain.tsuzuki.collections.cache

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

interface PersistentCatalogCacheStore {
    suspend fun get(key: CatalogCacheKey, now: Long): CatalogCacheLookup

    suspend fun put(
        key: CatalogCacheKey,
        page: CatalogPage,
        fetchedAt: Long,
        ttlMillis: Long,
        staleWhileRevalidateMillis: Long,
    )

    suspend fun remove(key: CatalogCacheKey)

    suspend fun prune(now: Long)
}
