package tachiyomi.domain.tsuzuki.collections.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

class InFlightQueryDeduplicator(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<CatalogCacheKey, Deferred<Result<CatalogPage>>>()

    suspend fun execute(
        key: CatalogCacheKey,
        producer: suspend () -> Result<CatalogPage>,
    ): Result<CatalogPage> {
        val deferred = mutex.withLock {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                producer()
            }.also { created ->
                inFlight[key] = created
                created.invokeOnCompletion {
                    scope.launch {
                        mutex.withLock {
                            if (inFlight[key] === created) {
                                inFlight.remove(key)
                            }
                        }
                    }
                }
                created.start()
            }
        }

        return try {
            deferred.await()
        } finally {
            if (deferred.isCompleted) {
                mutex.withLock {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
            }
        }
    }

    suspend fun activeCount(): Int = mutex.withLock { inFlight.size }
}
