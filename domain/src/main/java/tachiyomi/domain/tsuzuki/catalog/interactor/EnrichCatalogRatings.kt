package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating

@Inject
class EnrichCatalogRatings(
    private val registry: IntegrationRegistry,
) {

    suspend fun execute(items: List<CatalogItem>): List<CatalogItem> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope items

        registry.awaitReady()
        val providers = registry.ratingsProviders()
        if (providers.isEmpty()) return@coroutineScope items

        val lookupGate = Semaphore(MAX_CONCURRENT_RATING_LOOKUPS)
        items.map { item ->
            async {
                enrichItem(
                    item = item,
                    providers = providers,
                    lookupGate = lookupGate,
                )
            }
        }.awaitAll()
    }

    private suspend fun enrichItem(
        item: CatalogItem,
        providers: List<RatingsProvider>,
        lookupGate: Semaphore,
    ): CatalogItem = coroutineScope {
        val ownScores = item.scores
            .ifEmpty { listOfNotNull(item.score) }
            .distinctBy(CatalogScore::provider)
        val identities = buildMap {
            if (item.provider.isNotBlank() && item.providerId.isNotBlank()) {
                put(item.provider, item.providerId)
            }
            item.externalIds.forEach { (provider, externalId) ->
                if (provider.isNotBlank() && externalId.isNotBlank()) {
                    put(provider, externalId)
                }
            }
        }
        val ratedProviders = ownScores.mapTo(mutableSetOf(), CatalogScore::provider)

        val fetchedRatings = providers
            .filterNot { provider -> provider.integrationId.value in ratedProviders }
            .map { provider ->
                async {
                    lookupGate.withPermit {
                        provider.safeRatingsFor(identities)
                    }
                }
            }
            .awaitAll()
            .flatten()

        val fetchedScores = fetchedRatings.map { rating ->
            CatalogScore(
                provider = rating.providerId,
                value = rating.value,
                maxValue = rating.scaleMax,
            )
        }
        val scores = (ownScores + fetchedScores)
            .distinctBy(CatalogScore::provider)

        val resolvedExternalIds = item.externalIds.toMutableMap()
        fetchedRatings.forEach { rating ->
            rating.externalId
                ?.takeIf(String::isNotBlank)
                ?.let { externalId ->
                    resolvedExternalIds.putIfAbsent(rating.providerId, externalId)
                }
        }

        item.copy(
            score = scores.firstOrNull(),
            scores = scores,
            externalIds = resolvedExternalIds,
        )
    }

    private suspend fun RatingsProvider.safeRatingsFor(
        identities: Map<String, String>,
    ): List<ExternalRating> {
        return try {
            val result = ratingsFor(identities)
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            result.getOrElse { emptyList() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_RATING_LOOKUPS = 6
    }
}
