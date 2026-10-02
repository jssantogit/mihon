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
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import java.util.LinkedHashMap

@SingleIn(AppScope::class)
class RatingEnrichmentCache private constructor(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val positiveTtlMillis: Long,
    private val negativeTtlMillis: Long,
    private val maxEntries: Int,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    internal constructor(
        scope: CoroutineScope,
        clock: () -> Long,
        positiveTtlMillis: Long,
        negativeTtlMillis: Long,
        maxEntries: Int,
    ) : this(
        scope = scope,
        clock = clock,
        positiveTtlMillis = positiveTtlMillis,
        negativeTtlMillis = negativeTtlMillis,
        maxEntries = maxEntries,
        constructorMarker = Unit,
    )

    constructor(scope: CoroutineScope) : this(
        scope = scope,
        clock = { System.currentTimeMillis() },
        positiveTtlMillis = DEFAULT_POSITIVE_TTL_MILLIS,
        negativeTtlMillis = DEFAULT_NEGATIVE_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
        constructorMarker = Unit,
    )

    @Inject
    constructor() : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { System.currentTimeMillis() },
        positiveTtlMillis = DEFAULT_POSITIVE_TTL_MILLIS,
        negativeTtlMillis = DEFAULT_NEGATIVE_TTL_MILLIS,
        maxEntries = DEFAULT_MAX_ENTRIES,
        constructorMarker = Unit,
    )

    private data class ItemIdentity(
        val provider: String,
        val providerId: String,
        val externalIds: List<Pair<String, String>>,
        val title: String,
        val titles: List<Pair<String, String>>,
        val authors: List<String>,
        val artists: List<String>,
        val startDate: String?,
        val endDate: String?,
        val format: String,
    )

    private data class ProviderItemKey(
        val providerId: String,
        val configurationFingerprint: String,
        val item: ItemIdentity,
    )

    private data class Entry<T>(
        val value: T,
        val expiresAt: Long,
    )

    private data class InFlight<T>(
        val deferred: CompletableDeferred<Result<T>>,
        var waiters: Int = 1,
        var job: Job? = null,
    )

    private val mutex = Mutex()
    private val identityEntries = LinkedHashMap<ProviderItemKey, Entry<Map<String, String>>>(16, 0.75f, true)
    private val ratingEntries = LinkedHashMap<ProviderItemKey, Entry<CatalogRatingMatch?>>(16, 0.75f, true)
    private val identityInFlight =
        mutableMapOf<ProviderItemKey, InFlight<Map<String, String>>>()
    private val ratingInFlight =
        mutableMapOf<ProviderItemKey, InFlight<CatalogRatingMatch?>>()

    suspend fun resolveExternalIds(
        item: CatalogItem,
        provider: RatingsProvider,
        configurationFingerprint: String,
    ): Result<Map<String, String>> {
        knownExternalId(item, provider)?.let { externalId ->
            return Result.success(mapOf(provider.integrationId.value to externalId))
        }

        val key = key(item, provider, configurationFingerprint)
        var owner = false
        val flight = mutex.withLock {
            identityEntries[key]
                ?.takeIf { it.expiresAt > clock() }
                ?.let { return Result.success(it.value) }
            identityEntries.remove(key)

            identityInFlight[key]?.also { it.waiters++ }
                ?: InFlight<Map<String, String>>(
                    deferred = CompletableDeferred(),
                ).also {
                    identityInFlight[key] = it
                    owner = true
                }
        }

        if (owner) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    provider.resolveExternalIds(item)
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                mutex.withLock {
                    if (identityInFlight[key] === flight) {
                        identityInFlight.remove(key)
                        result.getOrNull()?.let { value ->
                            identityEntries[key] = Entry(
                                value = value,
                                expiresAt = clock() + ttlFor(value.isNotEmpty()),
                            )
                            trimToLimit(identityEntries)
                        }
                        flight.deferred.complete(result)
                    }
                }
            }
            val shouldStart = mutex.withLock {
                if (identityInFlight[key] === flight) {
                    flight.job = job
                    true
                } else {
                    false
                }
            }
            if (shouldStart) job.start() else job.cancel()
        }

        return awaitFlight(key, flight, identityInFlight)
    }

    suspend fun ratingFor(
        item: CatalogItem,
        provider: RatingsProvider,
        configurationFingerprint: String,
    ): Result<CatalogRatingMatch?> {
        val key = key(item, provider, configurationFingerprint)
        var owner = false
        val flight = mutex.withLock {
            ratingEntries[key]
                ?.takeIf { it.expiresAt > clock() }
                ?.let { return Result.success(it.value) }
            ratingEntries.remove(key)

            ratingInFlight[key]?.also { it.waiters++ }
                ?: InFlight<CatalogRatingMatch?>(
                    deferred = CompletableDeferred(),
                ).also {
                    ratingInFlight[key] = it
                    owner = true
                }
        }

        if (owner) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    provider.ratingFor(item)
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                mutex.withLock {
                    if (ratingInFlight[key] === flight) {
                        ratingInFlight.remove(key)
                        if (result.isSuccess) {
                            val value = result.getOrNull()
                            ratingEntries[key] = Entry(
                                value = value,
                                expiresAt = clock() + ttlFor(value != null),
                            )
                            trimToLimit(ratingEntries)
                        }
                        flight.deferred.complete(result)
                    }
                }
            }
            val shouldStart = mutex.withLock {
                if (ratingInFlight[key] === flight) {
                    flight.job = job
                    true
                } else {
                    false
                }
            }
            if (shouldStart) job.start() else job.cancel()
        }

        return awaitFlight(key, flight, ratingInFlight)
    }

    suspend fun clear() {
        mutex.withLock {
            identityEntries.clear()
            ratingEntries.clear()
            val error = IllegalStateException("Rating enrichment cache cleared")
            identityInFlight.values.forEach { flight ->
                flight.job?.cancel()
                flight.deferred.complete(Result.failure(error))
            }
            ratingInFlight.values.forEach { flight ->
                flight.job?.cancel()
                flight.deferred.complete(Result.failure(error))
            }
            identityInFlight.clear()
            ratingInFlight.clear()
        }
    }

    private suspend fun <T> awaitFlight(
        key: ProviderItemKey,
        flight: InFlight<T>,
        flights: MutableMap<ProviderItemKey, InFlight<T>>,
    ): Result<T> {
        try {
            return flight.deferred.await()
        } finally {
            withContext(NonCancellable) {
                var orphanedJob: Job? = null
                mutex.withLock {
                    if (flights[key] === flight && !flight.deferred.isCompleted) {
                        flight.waiters--
                        if (flight.waiters <= 0) {
                            flights.remove(key)
                            orphanedJob = flight.job
                        }
                    }
                }
                orphanedJob?.cancel()
            }
        }
    }

    private fun knownExternalId(
        item: CatalogItem,
        provider: RatingsProvider,
    ): String? = when {
        item.provider == provider.integrationId.value -> item.providerId
        else -> item.externalIds[provider.integrationId.value]
    }?.takeIf(String::isNotBlank)

    private fun key(
        item: CatalogItem,
        provider: RatingsProvider,
        configurationFingerprint: String,
    ) = ProviderItemKey(
        providerId = provider.integrationId.value,
        configurationFingerprint = configurationFingerprint,
        item = ItemIdentity(
            provider = item.provider,
            providerId = item.providerId,
            externalIds = item.externalIds.toSortedMap().toList(),
            title = item.title,
            titles = item.titles.toSortedMap().toList(),
            authors = item.authors.filter(String::isNotBlank).sorted(),
            artists = item.artists.filter(String::isNotBlank).sorted(),
            startDate = item.startDate,
            endDate = item.endDate,
            format = item.format.name,
        ),
    )

    private fun ttlFor(positive: Boolean): Long =
        if (positive) positiveTtlMillis else negativeTtlMillis

    private fun <T> trimToLimit(entries: LinkedHashMap<ProviderItemKey, Entry<T>>) {
        while (entries.size > maxEntries.coerceAtLeast(0)) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_POSITIVE_TTL_MILLIS = 15 * 60 * 1000L
        const val DEFAULT_NEGATIVE_TTL_MILLIS = 2 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
