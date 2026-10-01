package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.hikka.HikkaIntegrationApi
import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.matchRatingOnlyCandidate
import kotlin.coroutines.cancellation.CancellationException

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
class HikkaIntegrationProvider private constructor(
    private val api: HikkaIntegrationApi,
) : RatingsProvider {

    @Inject
    constructor(trackerManager: TrackerManager) : this(
        api = trackerManager.hikka.integrationApi,
    )

    override val integrationId = IntegrationId("hikka")

    override suspend fun ratings(externalId: String): Result<List<ExternalRating>> = capture {
        listOfNotNull(
            api.getMangaDetailsPublic(externalId)
                ?.toExternalRating(),
        )
    }

    override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> = capture {
        val exactId = when {
            item.provider == integrationId.value -> item.providerId
            else -> item.externalIds[integrationId.value]
        }?.takeIf(String::isNotBlank)

        if (exactId != null) {
            return@capture api.getMangaDetailsPublic(exactId)
                ?.toRatingMatch(verifiedIdentity = true)
        }

        val candidates = api.searchPublic(item.title)
            .take(RATING_IDENTITY_SEARCH_LIMIT)
            .map { candidate -> candidate.toCatalogItem() }

        val knownMalId = when {
            item.provider == "mal" -> item.providerId
            else -> item.externalIds["mal"]
        }?.takeIf(String::isNotBlank)

        val providerMapped = knownMalId?.let { malId ->
            candidates.singleOrNull { candidate -> candidate.externalIds["mal"] == malId }
        }
        val candidate = providerMapped ?: matchRatingOnlyCandidate(item, candidates)
            ?: return@capture null

        candidate.toRatingMatch(verifiedIdentity = providerMapped != null)
    }

    private fun HKManga.toCatalogItem(): CatalogItem {
        val primaryTitle = titleUa?.takeIf(String::isNotBlank)
            ?: titleEn?.takeIf(String::isNotBlank)
            ?: titleOriginal
        val alternateTitles = listOfNotNull(titleOriginal, titleEn, titleUa)
            .filter(String::isNotBlank)
            .distinct()

        return CatalogItem(
            provider = integrationId.value,
            providerId = slug,
            title = primaryTitle,
            titles = alternateTitles
                .mapIndexed { index, value -> "alternate_$index" to value }
                .toMap(),
            score = nativeScore
                .takeIf { it > 0.0 && nativeScoredBy > 0 }
                ?.let { value ->
                    CatalogScore(
                        provider = integrationId.value,
                        value = value,
                        maxValue = HIKKA_SCORE_MAX,
                        voteCount = nativeScoredBy,
                    )
                },
            externalIds = malId?.let { mapOf("mal" to it.toString()) }.orEmpty(),
            startDate = year?.toString(),
        )
    }

    private fun HKManga.toExternalRating(): ExternalRating? {
        val value = nativeScore.takeIf { it > 0.0 && nativeScoredBy > 0 } ?: return null
        return ExternalRating(
            providerId = integrationId.value,
            label = "Hikka",
            value = value,
            scaleMax = HIKKA_SCORE_MAX,
            voteCount = nativeScoredBy,
        )
    }

    private fun HKManga.toRatingMatch(verifiedIdentity: Boolean): CatalogRatingMatch? =
        toExternalRating()?.let { rating ->
            CatalogRatingMatch(
                externalId = slug,
                rating = rating,
                verifiedIdentity = verifiedIdentity,
            )
        }

    private fun CatalogItem.toRatingMatch(verifiedIdentity: Boolean): CatalogRatingMatch? {
        val score = score ?: return null
        return CatalogRatingMatch(
            externalId = providerId,
            rating = ExternalRating(
                providerId = integrationId.value,
                label = "Hikka",
                value = score.value,
                scaleMax = score.maxValue,
                voteCount = score.voteCount,
            ),
            verifiedIdentity = verifiedIdentity,
        )
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    companion object {
        private const val HIKKA_SCORE_MAX = 10.0
        private const val RATING_IDENTITY_SEARCH_LIMIT = 10

        internal fun forTest(api: HikkaIntegrationApi): HikkaIntegrationProvider =
            HikkaIntegrationProvider(api)
    }
}
