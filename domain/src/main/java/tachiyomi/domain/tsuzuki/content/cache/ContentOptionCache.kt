package tachiyomi.domain.tsuzuki.content.cache

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentOption
import java.util.LinkedHashMap
import kotlin.time.Clock

data class ContentOptionCacheKey(
    val canonicalTitleId: String,
    val canonicalChapterId: String,
    val addonId: AddonId,
)

@SingleIn(AppScope::class)
class ContentOptionCache internal constructor(
    private val clock: () -> Long,
    private val successTtlMillis: Long,
    private val emptyTtlMillis: Long,
    private val maxEntries: Int,
) {
    @Inject
    constructor() : this(
        clock = { Clock.System.now().toEpochMilliseconds() },
        successTtlMillis = DEFAULT_SUCCESS_TTL_MILLIS,
        emptyTtlMillis = DEFAULT_EMPTY_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
    )

    private data class Entry(
        val options: List<ContentOption>,
        val expiresAt: Long,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<ContentOptionCacheKey, Entry>(16, 0.75f, true)

    suspend fun get(key: ContentOptionCacheKey): List<ContentOption>? {
        val now = clock()
        return mutex.withLock {
            val entry = entries[key] ?: return@withLock null
            if (entry.expiresAt <= now) {
                entries.remove(key)
                return@withLock null
            }
            entry.options
        }
    }

    suspend fun put(
        key: ContentOptionCacheKey,
        options: List<ContentOption>,
    ) {
        val ttl = if (options.isEmpty()) emptyTtlMillis else successTtlMillis
        if (ttl <= 0L || maxEntries <= 0) return
        val entry = Entry(
            options = options.toList(),
            expiresAt = clock() + ttl,
        )
        mutex.withLock {
            entries[key] = entry
            while (entries.size > maxEntries) {
                val eldest = entries.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
        }
    }

    suspend fun invalidateAddon(addonId: AddonId) {
        mutex.withLock {
            entries.keys.removeAll { it.addonId == addonId }
        }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    companion object {
        const val DEFAULT_SUCCESS_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_EMPTY_TTL_MILLIS = 30 * 1000L
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
