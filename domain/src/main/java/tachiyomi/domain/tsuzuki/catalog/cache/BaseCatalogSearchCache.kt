package tachiyomi.domain.tsuzuki.catalog.cache

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import java.util.LinkedHashMap

@SingleIn(AppScope::class)
class BaseCatalogSearchCache private constructor(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMillis: Long,
    private val maxEntries: Int,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    data class Key(
        val query: CatalogQuery,
        val providerIds: List<String>,
        val configurationFingerprint: String,
    )

    data class LoadResult(
        val items: List<CatalogItem>,
        val cacheable: Boolean,
    )

    private data class Entry(
        val items: List<CatalogItem>,
        val expiresAt: Long,
    )

    private data class InFlight(
        val deferred: CompletableDeferred<Result<LoadResult>>,
        var waiters: Int = 1,
        var job: Job? = null,
    )

    internal constructor(
        scope: CoroutineScope,
        clock: () -> Long,
        ttlMillis: Long,
        maxEntries: Int,
    ) : this(
        scope = scope,
        clock = clock,
        ttlMillis = ttlMillis,
        maxEntries = maxEntries,
        constructorMarker = Unit,
    )

    constructor(scope: CoroutineScope) : this(
        scope = scope,
        clock = { System.currentTimeMillis() },
        ttlMillis = DEFAULT_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
        constructorMarker = Unit,
    )

    @Inject
    constructor() : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { System.currentTimeMillis() },
        ttlMillis = DEFAULT_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
        constructorMarker = Unit,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<Key, InFlight>()

    suspend fun get(key: Key): List<CatalogItem>? = mutex.withLock {
        cachedLocked(key)
    }

    suspend fun put(key: Key, items: List<CatalogItem>) {
        if (ttlMillis <= 0 || maxEntries <= 0) return
        mutex.withLock { putLocked(key, items) }
    }

    suspend fun getOrFetch(
        key: Key,
        fetch: suspend () -> LoadResult,
    ): List<CatalogItem> {
        var owner = false
        val flight = mutex.withLock {
            cachedLocked(key)?.let { return it }
            inFlight[key]?.also { it.waiters++ }
                ?: InFlight(CompletableDeferred()).also {
                    inFlight[key] = it
                    owner = true
                }
        }

        if (owner) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    Result.success(fetch())
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                mutex.withLock {
                    if (inFlight[key] === flight) {
                        inFlight.remove(key)
                        result.getOrNull()
                            ?.takeIf(LoadResult::cacheable)
                            ?.let { loaded -> putLocked(key, loaded.items) }
                        flight.deferred.complete(result)
                    }
                }
            }
            val shouldStart = mutex.withLock {
                if (inFlight[key] === flight) {
                    flight.job = job
                    true
                } else {
                    false
                }
            }
            if (shouldStart) job.start() else job.cancel()
        }

        return try {
            flight.deferred.await().getOrThrow().items
        } finally {
            withContext(NonCancellable) {
                var orphaned: Job? = null
                mutex.withLock {
                    if (inFlight[key] === flight && !flight.deferred.isCompleted) {
                        flight.waiters--
                        if (flight.waiters <= 0) {
                            inFlight.remove(key)
                            orphaned = flight.job
                        }
                    }
                }
                orphaned?.cancel()
            }
        }
    }

    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            val error = IllegalStateException("Base catalog search cache cleared")
            inFlight.values.forEach { flight ->
                flight.job?.cancel()
                flight.deferred.complete(Result.failure(error))
            }
            inFlight.clear()
        }
    }

    private fun cachedLocked(key: Key): List<CatalogItem>? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= clock()) {
            entries.remove(key)
            return null
        }
        return entry.items
    }

    private fun putLocked(key: Key, items: List<CatalogItem>) {
        if (ttlMillis <= 0 || maxEntries <= 0) return
        entries[key] = Entry(
            items = items,
            expiresAt = clock() + ttlMillis,
        )
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
