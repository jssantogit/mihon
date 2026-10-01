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
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch

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
     * Every enabled ratings provider may contribute when it can resolve an exact external
     * identity. Provider-specific lookup implementations may use search as discovery, but must
     * still prove identity through explicit IDs/mappings before returning a match.
     */
    suspend fun enrichRatings(items: List<CatalogItem>): List<CatalogItem> = coroutineScope {
        val providers = registry.ratingsProviders()
        if (providers.isEmpty()) {
            return@coroutineScope items.map { item ->
                item.copy(
                    score = null,
                    scores = emptyList(),
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
                            try {
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

    private fun <T> Result<T>.getOrNullPreservingCancellation(): T? {
        val error = exceptionOrNull()
        if (error is CancellationException) throw error
        return getOrNull()
    }

    private companion object {
        const val RATING_LOOKUP_CONCURRENCY = 4
        val RATING_PROVIDER_ORDER = listOf("mal", "kitsu", "mangaupdates", "bangumi")
    }
}
