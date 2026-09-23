package tachiyomi.domain.tsuzuki.collections.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

class MemoryCatalogCache(
    private val maxEntries: Int = 128,
) {
    init {
        require(maxEntries > 0) { "Memory cache maxEntries must be positive" }
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<CatalogCacheKey, Entry>()

    suspend fun get(
        key: CatalogCacheKey,
        now: Long,
    ): CatalogCacheLookup = mutex.withLock {
        val entry = entries[key] ?: return@withLock CatalogCacheLookup.Miss
        val freshness = classifyCacheWindow(now, entry.expiresAt, entry.staleUntil)
        if (freshness == null) {
            entries.remove(key)
            CatalogCacheLookup.Miss
        } else {
            CatalogCacheLookup.Hit(
                page = entry.page.copy(items = entry.page.items.toList()),
                freshness = freshness,
                fetchedAt = entry.fetchedAt,
            )
        }
    }

    suspend fun put(
        key: CatalogCacheKey,
        page: CatalogPage,
        fetchedAt: Long,
        ttlMillis: Long,
        staleWhileRevalidateMillis: Long,
    ) {
        require(ttlMillis >= 0) { "Cache TTL cannot be negative" }
        require(staleWhileRevalidateMillis >= 0) { "Cache stale window cannot be negative" }

        val expiresAt = safeCacheDeadline(fetchedAt, ttlMillis)
        val staleUntil = safeCacheDeadline(expiresAt, staleWhileRevalidateMillis)
        val copy = page.copy(items = page.items.toList())

        mutex.withLock {
            if (key !in entries && entries.size >= maxEntries) {
                val oldestKey = entries.minByOrNull { it.value.fetchedAt }?.key
                if (oldestKey != null) {
                    entries.remove(oldestKey)
                }
            }
            entries[key] = Entry(
                page = copy,
                fetchedAt = fetchedAt,
                expiresAt = expiresAt,
                staleUntil = staleUntil,
            )
        }
    }

    suspend fun remove(key: CatalogCacheKey) {
        mutex.withLock {
            entries.remove(key)
        }
    }

    suspend fun prune(now: Long) {
        mutex.withLock {
            entries.entries.removeAll { (_, entry) ->
                classifyCacheWindow(now, entry.expiresAt, entry.staleUntil) == null
            }
        }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    private data class Entry(
        val page: CatalogPage,
        val fetchedAt: Long,
        val expiresAt: Long,
        val staleUntil: Long,
    )
}
