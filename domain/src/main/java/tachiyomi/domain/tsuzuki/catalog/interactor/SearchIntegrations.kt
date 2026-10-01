package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
) {

    suspend fun execute(query: CatalogQuery): List<CatalogItem> = coroutineScope {
        registry.searchProviders()
            .map { provider ->
                async {
                    provider.search(query)
                        .getOrElse { CatalogPage(items = emptyList(), hasNextPage = false) }
                        .items
                }
            }
            .awaitAll()
            .flatten()
            // Exact provider ids and provider-published cross-provider mappings are safe.
            // Title similarity alone remains intentionally insufficient.
            .let(::mergeCatalogItemsByVerifiedIdentity)
            .let { items -> enrichRatings(items) }
    }

    /**
     * Ratings describe the canonical work, not the catalog that happened to list it.
     *
     * Every enabled ratings provider may contribute. Exact provider IDs and provider-published
     * mappings remain the only identities persisted into the canonical graph. Providers may also
     * contribute a non-persistent rating-only match when title/alias is corroborated by publication
     * year or creator identity and the candidate is unambiguous.
     */
    suspend fun enrichRatings(items: List<CatalogItem>): List<CatalogItem> = coroutineScope {
        val providers = registry.ratingsProviders()
        if (providers.isEmpty()) {
            return@coroutineScope items.map { item ->
                item.copy(
                    score = null,
                    scores = emptyList(),
                    tsuzukiRating = null,
                )
            }
        }

        val activeProviderIds = providers.map { it.integrationId.value }.toSet()
        // Catalog rows may need provider detail lookups; keep network pressure bounded.
        val semaphore = Semaphore(RATING_LOOKUP_CONCURRENCY)

        items.map { item ->
            async {
                val resolvedIdentities = providers
                    .map { provider ->
                        async {
                            try {
                                semaphore.withPermit {
                                    provider.resolveExternalIds(item)
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
                                candidates = items,
                            ) ?: try {
                                semaphore.withPermit {
                                    provider.ratingFor(identifiedItem)
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
        }.awaitAll()
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
        val RATING_PROVIDER_ORDER = listOf("mal", "kitsu", "mangaupdates", "bangumi", "shikimori", "hikka")
    }
}
