package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriIntegrationApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.matchRatingOnlyCandidate
import java.util.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
class ShikimoriIntegrationProvider private constructor(
    private val api: ShikimoriIntegrationApi,
    private val requestGate: ShikimoriRatingRequestGate,
) : RatingsProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.shikimori.integrationApi,
        requestGate = ShikimoriRatingRequestGate(),
    )

    override val integrationId = IntegrationId("shikimori")

    override suspend fun ratings(externalId: String): Result<List<ExternalRating>> = capture {
        val id = externalId.requireShikimoriId()
        listOfNotNull(
            requestGate.withPermit { api.getMangaDetailsPublic(id) }
                ?.toIntegrationCatalogItem(integrationId.value)
                ?.toExternalRating(),
        )
    }

    override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> = capture {
        val exactId = when {
            item.provider == integrationId.value -> item.providerId
            else -> item.externalIds[integrationId.value]
        }?.takeIf(String::isNotBlank)

        if (exactId != null) {
            val details = requestGate.withPermit {
                api.getMangaDetailsPublic(exactId.requireShikimoriId())
            }?.toIntegrationCatalogItem(integrationId.value)
            return@capture details?.toRatingMatch(verifiedIdentity = true)
        }

        val candidates = requestGate.withPermit { api.searchPublic(item.title) }
            .take(RATING_IDENTITY_SEARCH_LIMIT)
            .map { candidate -> candidate.toIntegrationCatalogItem(integrationId.value) }
        matchRatingOnlyCandidate(item, candidates)
            ?.toRatingMatch(verifiedIdentity = false)
    }

    private fun CatalogItem.toExternalRating(): ExternalRating? {
        val score = score ?: return null
        return ExternalRating(
            providerId = integrationId.value,
            label = "Shikimori",
            value = score.value,
            scaleMax = score.maxValue,
        )
    }

    private fun CatalogItem.toRatingMatch(verifiedIdentity: Boolean): CatalogRatingMatch? =
        toExternalRating()?.let { rating ->
            CatalogRatingMatch(
                externalId = providerId,
                rating = rating,
                verifiedIdentity = verifiedIdentity,
            )
        }

    private fun String.requireShikimoriId(): Int =
        toIntOrNull() ?: throw IllegalArgumentException("Invalid Shikimori external id: $this")

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    companion object {
        private const val RATING_IDENTITY_SEARCH_LIMIT = 10

        internal fun forTest(api: ShikimoriIntegrationApi): ShikimoriIntegrationProvider =
            ShikimoriIntegrationProvider(
                api = api,
                requestGate = ShikimoriRatingRequestGate(),
            )
    }
}

/**
 * Shikimori documents both a 5 requests/second and a 90 requests/minute API budget.
 * Reserve permits in a sliding window so concurrent catalog enrichment stays inside both limits.
 */
internal class ShikimoriRatingRequestGate(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val recentRequests = ArrayDeque<Long>()

    suspend fun <T> withPermit(block: suspend () -> T): T {
        awaitPermit()
        return block()
    }

    private suspend fun awaitPermit() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = nowMillis()
                while (recentRequests.isNotEmpty() && now - recentRequests.first() >= MINUTE_WINDOW_MS) {
                    recentRequests.removeFirst()
                }

                val recentSecond = recentRequests.filter { timestamp ->
                    now - timestamp < SECOND_WINDOW_MS
                }
                val secondWait = if (recentSecond.size >= MAX_REQUESTS_PER_SECOND) {
                    SECOND_WINDOW_MS - (now - recentSecond.first())
                } else {
                    0L
                }
                val minuteWait = if (recentRequests.size >= MAX_REQUESTS_PER_MINUTE) {
                    MINUTE_WINDOW_MS - (now - recentRequests.first())
                } else {
                    0L
                }
                val requiredWait = maxOf(secondWait, minuteWait)

                if (requiredWait <= 0L) {
                    recentRequests.addLast(now)
                    0L
                } else {
                    requiredWait.coerceAtLeast(1L)
                }
            }

            if (waitMillis <= 0L) return
            pause(waitMillis)
        }
    }

    private companion object {
        const val MAX_REQUESTS_PER_SECOND = 5
        const val MAX_REQUESTS_PER_MINUTE = 90
        const val SECOND_WINDOW_MS = 1_000L
        const val MINUTE_WINDOW_MS = 60_000L
    }
}
