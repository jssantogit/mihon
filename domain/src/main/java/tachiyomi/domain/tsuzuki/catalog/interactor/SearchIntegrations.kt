package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.catalog.cache.RatingEnrichmentCache
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.catalog.model.mergeCatalogItemsByVerifiedIdentity
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.TSUZUKI_INTEGRATION_ID
import tachiyomi.domain.tsuzuki.integration.interactor.ComputeTsuzukiRating
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRatingSource
import tachiyomi.domain.tsuzuki.integration.model.matchRatingOnlyCandidate

@Inject
class SearchIntegrations(
    private val registry: IntegrationRegistry,
    private val ratingEnrichmentCache: RatingEnrichmentCache = RatingEnrichmentCache(),
) {

    suspend fun execute(query: CatalogQuery): List<CatalogItem> =
        enrichRatings(executeBase(query))

    suspend fun executeBase(query: CatalogQuery): List<CatalogItem> =
        executeBaseProgressively(query) { }

    suspend fun executeBaseProgressively(
        query: CatalogQuery,
        onItems: suspend (List<CatalogItem>) -> Unit,
    ): List<CatalogItem> = coroutineScope {
        val providers = registry.searchProviders()
        if (providers.isEmpty()) return@coroutineScope emptyList()

        val completed = Channel<Pair<Int, List<CatalogItem>>>(providers.size)
        val providerItems = MutableList<List<CatalogItem>?>(providers.size) { null }
        providers.forEachIndexed { index, provider ->
            launch {
                val result = provider.search(query)
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                completed.send(
                    index to result
                        .getOrElse { CatalogPage(items = emptyList(), hasNextPage = false) }
                        .items,
                )
            }
        }

        var merged = emptyList<CatalogItem>()
        repeat(providers.size) {
            val (index, items) = completed.receive()
            providerItems[index] = items
            merged = providerItems
                .filterNotNull()
                .flatten()
                // Exact provider ids and provider-published cross-provider mappings are safe.
                // Title similarity alone remains intentionally insufficient.
                .let(::mergeCatalogItemsByVerifiedIdentity)
            onItems(merged)
        }
        merged
    }

    /**
     * Ratings describe the canonical work, not the catalog that happened to list it.
     *
     * Every enabled ratings provider may contribute. Exact provider IDs and provider-published
     * mappings remain the only identities persisted into the canonical graph. Providers may also
     * contribute a non-persistent rating-only match when title/alias is corroborated by publication
     * year or creator identity and the candidate is unambiguous.
     */
    suspend fun enrichRatings(items: List<CatalogItem>): List<CatalogItem> =
        enrichRatingsProgressively(items) { _, _ -> }

    suspend fun enrichRatingsProgressively(
        items: List<CatalogItem>,
        onItem: suspend (index: Int, item: CatalogItem) -> Unit,
    ): List<CatalogItem> = coroutineScope {
        val providers = registry.ratingsProviders()
        if (providers.isEmpty()) {
            val cleared = items.map { item ->
                item.copy(
                    score = null,
                    scores = emptyList(),
                    tsuzukiRating = null,
                )
            }
            cleared.forEachIndexed { index, item -> onItem(index, item) }
            return@coroutineScope cleared
        }

        val activeProviderIds = providers.map { it.integrationId.value }.toSet()
        val configurationFingerprint = registry.configurationFingerprint()
        val semaphore = Semaphore(RATING_LOOKUP_CONCURRENCY)
        val publishMutex = Mutex()

        val itemGate = Semaphore(ITEM_ENRICHMENT_CONCURRENCY)
        val enrichedItems = items.toMutableList()
        items.indices
            .map { index ->
                async {
                    val enriched = itemGate.withPermit {
                        enrichRatingItem(
                            item = items[index],
                            candidates = items,
                            providers = providers,
                            activeProviderIds = activeProviderIds,
                            configurationFingerprint = configurationFingerprint,
                            semaphore = semaphore,
                        )
                    }
                    publishMutex.withLock {
                        onItem(index, enriched)
                    }
                    index to enriched
                }
            }
            .awaitAll()
            .forEach { (index, enriched) ->
                enrichedItems[index] = enriched
            }
        enrichedItems
    }

    private suspend fun enrichRatingItem(
        item: CatalogItem,
        candidates: List<CatalogItem>,
        providers: List<tachiyomi.domain.tsuzuki.integration.RatingsProvider>,
        activeProviderIds: Set<String>,
        configurationFingerprint: String,
        semaphore: Semaphore,
    ): CatalogItem = coroutineScope {
        val resolvedIdentities = providers
            .map { provider ->
                async {
                    try {
                        semaphore.withPermit {
                            ratingEnrichmentCache.resolveExternalIds(
                                item = item,
                                provider = provider,
                                configurationFingerprint = configurationFingerprint,
                            )
                                .getOrNullPreservingCancellation()
                                .orEmpty()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        emptyMap()
                    }
                }
            }
            .awaitAll()
            .fold(item.externalIds.toMutableMap()) { accumulated, identities ->
                accumulated.apply { putAll(identities) }
            }

        val identifiedItem = item.copy(externalIds = resolvedIdentities)
        val existingScores = item.scores
            .ifEmpty { listOfNotNull(item.score) }
            .filter { score -> score.provider in activeProviderIds }
            .distinctBy(CatalogScore::provider)
        val existingProviders = existingScores.map(CatalogScore::provider).toSet()

        val matches = providers
            .filterNot { provider -> provider.integrationId.value in existingProviders }
            .map { provider ->
                async {
                    localRatingMatch(
                        item = identifiedItem,
                        providerId = provider.integrationId.value,
                        candidates = candidates,
                    ) ?: try {
                        semaphore.withPermit {
                            ratingEnrichmentCache.ratingFor(
                                item = identifiedItem,
                                provider = provider,
                                configurationFingerprint = configurationFingerprint,
                            )
                                .getOrNullPreservingCancellation()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        val resolvedScores = matches.map { match ->
            CatalogScore(
                provider = match.rating.providerId,
                value = match.rating.value,
                maxValue = match.rating.scaleMax,
                voteCount = match.rating.voteCount,
                identityEvidence = match.identityEvidence,
            )
        }
        val scores = (existingScores + resolvedScores)
            .distinctBy(CatalogScore::provider)
            .sortedWith(
                compareBy<CatalogScore>(
                    { score ->
                        RATING_PROVIDER_ORDER.indexOf(score.provider)
                            .takeIf { index -> index >= 0 }
                            ?: Int.MAX_VALUE
                    },
                    CatalogScore::provider,
                ),
            )

        identifiedItem.copy(
            score = scores.firstOrNull(),
            scores = scores,
            tsuzukiRating = if (
                registry.isGlobalCapabilityActive(
                    TSUZUKI_INTEGRATION_ID,
                    IntegrationCapability.RATINGS,
                )
            ) {
                ComputeTsuzukiRating(
                    scores.map { score ->
                        TsuzukiRatingSource(
                            providerId = score.provider,
                            value = score.value,
                            maxValue = score.maxValue,
                            voteCount = score.voteCount,
                            identityEvidence = score.identityEvidence,
                        )
                    },
                )
            } else {
                null
            },
            externalIds = buildMap {
                putAll(identifiedItem.externalIds)
                matches
                    .filter(CatalogRatingMatch::verifiedIdentity)
                    .forEach { match ->
                        put(match.rating.providerId, match.externalId)
                    }
            },
        )
    }

    private fun localRatingMatch(
        item: CatalogItem,
        providerId: String,
        candidates: List<CatalogItem>,
    ): CatalogRatingMatch? {
        val providerCandidates = candidates.filter { candidate ->
            candidate.provider == providerId
        }
        val candidate = matchRatingOnlyCandidate(item, providerCandidates) ?: return null
        val score = candidate.scores
            .ifEmpty { listOfNotNull(candidate.score) }
            .firstOrNull { it.provider == providerId }
            ?: return null
        return CatalogRatingMatch(
            externalId = candidate.providerId,
            rating = ExternalRating(
                providerId = providerId,
                label = providerId,
                value = score.value,
                scaleMax = score.maxValue,
                voteCount = score.voteCount,
            ),
            verifiedIdentity = false,
        )
    }

    private fun <T> Result<T>.getOrNullPreservingCancellation(): T? {
        val error = exceptionOrNull()
        if (error is CancellationException) throw error
        return getOrNull()
    }

    private companion object {
        const val RATING_LOOKUP_CONCURRENCY = 4
        const val ITEM_ENRICHMENT_CONCURRENCY = 3
        val RATING_PROVIDER_ORDER = listOf("mal", "kitsu", "mangaupdates", "bangumi", "shikimori", "hikka")
    }
}
