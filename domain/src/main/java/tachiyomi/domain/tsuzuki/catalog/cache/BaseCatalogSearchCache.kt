package tachiyomi.domain.tsuzuki.catalog.cache

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import java.util.LinkedHashMap

@SingleIn(AppScope::class)
class BaseCatalogSearchCache internal constructor(
    private val clock: () -> Long,
    private val ttlMillis: Long,
    private val maxEntries: Int,
) {

    data class Key(
        val query: CatalogQuery,
        val providerIds: List<String>,
        val configurationFingerprint: String,
    )

    private data class Entry(
        val items: List<CatalogItem>,
        val expiresAt: Long,
    )

    @Inject
    constructor() : this(
        clock = { System.currentTimeMillis() },
        ttlMillis = DEFAULT_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    suspend fun get(key: Key): List<CatalogItem>? = mutex.withLock {
        val entry = entries[key] ?: return@withLock null
        if (entry.expiresAt <= clock()) {
            entries.remove(key)
            return@withLock null
        }
        entry.items
    }

    suspend fun put(key: Key, items: List<CatalogItem>) {
        if (ttlMillis <= 0 || maxEntries <= 0) return
        mutex.withLock {
            entries[key] = Entry(
                items = items,
                expiresAt = clock() + ttlMillis,
            )
            while (entries.size > maxEntries) {
                val iterator = entries.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
