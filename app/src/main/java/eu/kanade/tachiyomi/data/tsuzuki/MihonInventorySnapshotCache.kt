package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import java.util.LinkedHashMap
import kotlin.time.Clock

internal data class MihonInventoryKey(
    val canonicalTitleId: String,
    val mappingId: String,
    val sourceId: Long,
    val mangaId: Long,
    val sourceUrl: String,
    val language: String,
)

/**
 * Shared, bounded in-memory snapshot for chapter discovery and content resolution.
 * Concurrent lookups for the same binding share a single fetch; unrelated sources
 * remain concurrent. Failed and cancelled requests are never cached.
 */
@SingleIn(AppScope::class)
class MihonInventorySnapshotCache internal constructor(
    private val clock: () -> Long,
    private val ttlMillis: Long,
    private val maxEntries: Int,
) {
    @Inject
    constructor() : this(
        clock = { Clock.System.now().toEpochMilliseconds() },
        ttlMillis = DEFAULT_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
    )

    private data class Entry(
        val value: SourceChapterInventory,
        val expiresAt: Long,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<MihonInventoryKey, Entry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<MihonInventoryKey, CompletableDeferred<Result<SourceChapterInventory>>>()

    internal suspend fun getOrFetch(
        key: MihonInventoryKey,
        refresh: Boolean = false,
        fetch: suspend () -> Result<SourceChapterInventory>,
    ): Result<SourceChapterInventory> {
        var owner = false
        val pending = mutex.withLock {
            if (!refresh) {
                entries[key]?.let { cached ->
                    if (cached.expiresAt > clock()) {
                        return Result.success(cached.value)
                    }
                    entries.remove(key)
                }
            }

            inFlight[key] ?: CompletableDeferred<Result<SourceChapterInventory>>().also {
                inFlight[key] = it
                owner = true
            }
        }
        if (!owner) return pending.await()

        try {
            val result = try {
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            mutex.withLock {
                if (inFlight[key] === pending) {
                    inFlight.remove(key)
                    if (result.isSuccess && maxEntries > 0 && ttlMillis > 0) {
                        entries[key] = Entry(result.getOrThrow(), clock() + ttlMillis)
                        while (entries.size > maxEntries) {
                            val eldest = entries.entries.iterator()
                            eldest.next()
                            eldest.remove()
                        }
                    }
                }
            }
            pending.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            mutex.withLock {
                if (inFlight[key] === pending) inFlight.remove(key)
            }
            pending.cancel(cancelled)
            throw cancelled
        }
    }

    suspend fun invalidateTitle(canonicalTitleId: String) {
        mutex.withLock {
            entries.keys.removeAll { it.canonicalTitleId == canonicalTitleId }
        }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 128
    }
}
