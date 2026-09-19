package tachiyomi.domain.tsuzuki.collections.execution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheKey
import tachiyomi.domain.tsuzuki.collections.cache.InFlightQueryDeduplicator
import tachiyomi.domain.tsuzuki.collections.cache.MemoryCatalogCache
import tachiyomi.domain.tsuzuki.collections.scheduler.CollectionQueryScheduler

interface CollectionExecutionResources {
    val memoryCache: MemoryCatalogCache
    val inFlightDeduplicator: InFlightQueryDeduplicator
    val scheduler: CollectionQueryScheduler
    val refreshCoordinator: CatalogRefreshCoordinator
}

class CatalogRefreshCoordinator(
    private val scheduler: CollectionQueryScheduler,
) {
    private val lock = Mutex()
    private val refreshing = mutableSetOf<CatalogCacheKey>()

    suspend fun request(
        key: CatalogCacheKey,
        providerId: String,
        block: suspend () -> Unit,
    ): Boolean {
        val accepted = lock.withLock {
            refreshing.add(key)
        }
        if (!accepted) return false

        return try {
            scheduler.schedule(
                providerId = providerId,
                priority = tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority.BACKGROUND,
            ) {
                try {
                    block()
                } finally {
                    lock.withLock {
                        refreshing.remove(key)
                    }
                }
            }
            true
        } catch (failure: Throwable) {
            lock.withLock {
                refreshing.remove(key)
            }
            throw failure
        }
    }

    suspend fun activeCount(): Int = lock.withLock { refreshing.size }
}
