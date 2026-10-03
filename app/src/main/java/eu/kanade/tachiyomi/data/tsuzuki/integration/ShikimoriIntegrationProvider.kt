package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriCollectionQuery
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriIntegrationApi
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.collections.execution.PageCatalogFetcher
import tachiyomi.domain.tsuzuki.collections.execution.PageIndexOrigin
import tachiyomi.domain.tsuzuki.collections.execution.PageOffsetNormalizer
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.matchRatingOnlyCandidate
import kotlin.coroutines.cancellation.CancellationException

@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SearchProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<MetadataProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
class ShikimoriIntegrationProvider private constructor(
    private val api: ShikimoriIntegrationApi,
    private val requestGate: ShikimoriRequestGate,
) : SearchProvider, MetadataProvider, RatingsProvider {

    @Inject
    constructor(
        trackerManager: TrackerManager,
        requestGate: ShikimoriRequestGate,
    ) : this(
        api = trackerManager.shikimori.integrationApi,
        requestGate = requestGate,
    )

    override val integrationId = IntegrationId("shikimori")

    override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
        val text = query.query?.trim().orEmpty()
        if (text.isEmpty() || query.limit <= 0) {
            return Result.success(CatalogPage(emptyList(), hasNextPage = false))
        }

        return PageOffsetNormalizer.load(
            rawOffset = query.offset.coerceAtLeast(0),
            limit = query.limit,
            upstreamPageSize = SHIKIMORI_PAGE_SIZE,
            pageOrigin = PageIndexOrigin.ONE,
            fetcher = PageCatalogFetcher { page, pageSize ->
                capture {
                    val response = requestGate.withPermit {
                        api.collectionSearch(
                            ShikimoriCollectionQuery(
                                page = page,
                                limit = pageSize,
                                order = DEFAULT_SEARCH_ORDER,
                                kind = MANGA_KIND_EXCLUSIONS,
                                search = text,
                            ),
                        )
                    }
                    CatalogPage(
                        items = response.items.map { it.toIntegrationCatalogItem(integrationId.value) },
                        hasNextPage = response.hasNextPage,
                    )
                }
            },
        )
    }

    override suspend fun getDetails(externalId: String): Result<CatalogItem> = capture {
        val details = requestGate.withPermit {
            api.getMangaDetailsPublic(externalId.requireShikimoriId())
        } ?: error("Shikimori title not found: $externalId")
        details.toIntegrationCatalogItem(integrationId.value)
    }

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
            voteCount = score.voteCount,
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
        private const val SHIKIMORI_PAGE_SIZE = 50
        private const val DEFAULT_SEARCH_ORDER = "popularity"
        private const val MANGA_KIND_EXCLUSIONS = "!light_novel,!novel"

        internal fun forTest(
            api: ShikimoriIntegrationApi,
            requestGate: ShikimoriRequestGate = ShikimoriRequestGate(),
        ): ShikimoriIntegrationProvider =
            ShikimoriIntegrationProvider(
                api = api,
                requestGate = requestGate,
            )
    }
}
