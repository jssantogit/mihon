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

internal data class ContentOptionCacheToken(
    val key: ContentOptionCacheKey,
    val generation: Long,
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
    private val activeLookups = mutableMapOf<ContentOptionCacheKey, Int>()
    private val generations = mutableMapOf<ContentOptionCacheKey, Long>()

    suspend fun get(key: ContentOptionCacheKey): List<ContentOption>? {
        val now = clock()
        return mutex.withLock {
            val entry = entries[key] ?: return@withLock null
            if (entry.expiresAt <= now) {
                entries.remove(key)
                if (key !in activeLookups) generations.remove(key)
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
        mutex.withLock {
            putLocked(key, options, ttl)
        }
    }

    internal suspend fun beginLookup(key: ContentOptionCacheKey): ContentOptionCacheToken = mutex.withLock {
        activeLookups[key] = (activeLookups[key] ?: 0) + 1
        ContentOptionCacheToken(key, generations[key] ?: 0L)
    }

    /** A timed-out or invalidated provider result cannot repopulate the cache. */
    internal suspend fun putIfCurrent(
        token: ContentOptionCacheToken,
        options: List<ContentOption>,
    ): Boolean {
        val ttl = if (options.isEmpty()) emptyTtlMillis else successTtlMillis
        return mutex.withLock {
            if ((generations[token.key] ?: 0L) != token.generation) return@withLock false
            if (ttl > 0L && maxEntries > 0) putLocked(token.key, options, ttl)
            true
        }
    }

    internal suspend fun finishLookup(token: ContentOptionCacheToken) {
        mutex.withLock {
            val count = (activeLookups[token.key] ?: 1) - 1
            if (count <= 0) {
                activeLookups.remove(token.key)
                if (token.key !in entries) generations.remove(token.key)
            } else {
                activeLookups[token.key] = count
            }
        }
    }

    suspend fun invalidateAddon(addonId: AddonId) {
        mutex.withLock {
            invalidateLocked { it.addonId == addonId }
        }
    }

    internal suspend fun invalidate(key: ContentOptionCacheKey) {
        mutex.withLock { invalidateLocked { it == key } }
    }

    suspend fun invalidateTitleAddon(canonicalTitleId: String, addonId: AddonId) {
        mutex.withLock {
            invalidateLocked {
                it.canonicalTitleId == canonicalTitleId && it.addonId == addonId
            }
        }
    }

    suspend fun invalidateTitle(canonicalTitleId: String) {
        mutex.withLock {
            invalidateLocked { it.canonicalTitleId == canonicalTitleId }
        }
    }

    suspend fun invalidateChapter(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ) {
        mutex.withLock {
            invalidateLocked {
                it.canonicalTitleId == canonicalTitleId &&
                    it.canonicalChapterId == canonicalChapterId
            }
        }
    }

    suspend fun clear() {
        mutex.withLock { invalidateLocked { true } }
    }

    private fun putLocked(key: ContentOptionCacheKey, options: List<ContentOption>, ttl: Long) {
        entries[key] = Entry(
            options = options.toList(),
            expiresAt = clock() + ttl,
        )
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            if (eldest.hasNext()) {
                val evicted = eldest.next().key
                eldest.remove()
                if (evicted !in activeLookups) generations.remove(evicted)
            }
        }
    }

    private fun invalidateLocked(predicate: (ContentOptionCacheKey) -> Boolean) {
        val keys = (entries.keys + activeLookups.keys).filter(predicate).toSet()
        keys.forEach { key ->
            entries.remove(key)
            if (key in activeLookups) {
                generations[key] = (generations[key] ?: 0L) + 1
            } else {
                generations.remove(key)
            }
        }
    }

    companion object {
        const val DEFAULT_SUCCESS_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_EMPTY_TTL_MILLIS = 30 * 1000L
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
