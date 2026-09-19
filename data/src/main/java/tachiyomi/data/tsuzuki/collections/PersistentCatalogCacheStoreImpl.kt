package tachiyomi.data.tsuzuki.collections

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheKey
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheLookup
import tachiyomi.domain.tsuzuki.collections.cache.PersistentCatalogCacheStore
import tachiyomi.domain.tsuzuki.collections.cache.classifyCacheWindow
import tachiyomi.domain.tsuzuki.collections.cache.safeCacheDeadline

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PersistentCatalogCacheStoreImpl(
    private val database: Database,
) : PersistentCatalogCacheStore {

    override suspend fun get(
        key: CatalogCacheKey,
        now: Long,
    ): CatalogCacheLookup {
        val row = database.tsuzuki_catalog_cacheQueries
            .getTsuzukiCatalogCache(
                providerId = key.providerId,
                queryKey = key.normalizedQueryKey,
                sort = key.sort.name,
                rawOffset = key.rawOffset.toLong(),
                pageSize = key.pageSize.toLong(),
                mapper = { _, _, _, _, _, payloadJson, fetchedAt, expiresAt, staleUntil ->
                    CacheRow(
                        payloadJson = payloadJson,
                        fetchedAt = fetchedAt,
                        expiresAt = expiresAt,
                        staleUntil = staleUntil,
                    )
                },
            )
            .awaitAsOneOrNull()
            ?: return CatalogCacheLookup.Miss

        val freshness = classifyCacheWindow(
            now = now,
            expiresAt = row.expiresAt,
            staleUntil = row.staleUntil,
        )
        if (freshness == null) {
            remove(key)
            return CatalogCacheLookup.Miss
        }

        val page = try {
            CatalogPageCacheJsonCodec.decode(row.payloadJson)
        } catch (_: Exception) {
            remove(key)
            return CatalogCacheLookup.Miss
        }

        return CatalogCacheLookup.Hit(
            page = page,
            freshness = freshness,
            fetchedAt = row.fetchedAt,
        )
    }

    override suspend fun put(
        key: CatalogCacheKey,
        page: CatalogPage,
        fetchedAt: Long,
        ttlMillis: Long,
        staleWhileRevalidateMillis: Long,
    ) {
        val expiresAt = safeCacheDeadline(fetchedAt, ttlMillis)
        val staleUntil = safeCacheDeadline(expiresAt, staleWhileRevalidateMillis)

        database.tsuzuki_catalog_cacheQueries.upsertTsuzukiCatalogCache(
            providerId = key.providerId,
            queryKey = key.normalizedQueryKey,
            sort = key.sort.name,
            rawOffset = key.rawOffset.toLong(),
            pageSize = key.pageSize.toLong(),
            payloadJson = CatalogPageCacheJsonCodec.encode(page),
            fetchedAt = fetchedAt,
            expiresAt = expiresAt,
            staleUntil = staleUntil,
        )
    }

    override suspend fun remove(key: CatalogCacheKey) {
        database.tsuzuki_catalog_cacheQueries.deleteTsuzukiCatalogCache(
            providerId = key.providerId,
            queryKey = key.normalizedQueryKey,
            sort = key.sort.name,
            rawOffset = key.rawOffset.toLong(),
            pageSize = key.pageSize.toLong(),
        )
    }

    override suspend fun prune(now: Long) {
        database.tsuzuki_catalog_cacheQueries.pruneTsuzukiCatalogCache(now)
    }

    private data class CacheRow(
        val payloadJson: String,
        val fetchedAt: Long,
        val expiresAt: Long,
        val staleUntil: Long,
    )
}
