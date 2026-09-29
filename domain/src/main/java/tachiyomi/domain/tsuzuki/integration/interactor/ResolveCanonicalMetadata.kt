package tachiyomi.domain.tsuzuki.integration.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@Inject
class ResolveCanonicalMetadata(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val registry: IntegrationRegistry,
) {

    suspend fun execute(canonicalTitleId: String): Result<ResolvedMetadata> {
        return try {
            registry.awaitReady()
            val identities = canonicalTitleRepository
                .getExternalIdentities(canonicalTitleId)
                .filter(ExternalIdentity::verified)
                .sortedWith(
                    compareBy<ExternalIdentity>(
                        ExternalIdentity::provider,
                        ExternalIdentity::createdAt,
                        ExternalIdentity::externalId,
                    ),
                )
            if (identities.isEmpty()) {
                return Result.success(ResolvedMetadata())
            }

            val providers = METADATA_CAPABILITIES
                .flatMap(registry::metadataProviders)
                .distinctBy { it.integrationId }
                .associateBy { it.integrationId.value }

            val candidates = coroutineScope {
                identities.mapNotNull { identity ->
                    val provider = providers[identity.provider] ?: return@mapNotNull null
                    async {
                        provider.fetch(identity)
                    }
                }.awaitAll().filterNotNull()
            }

            Result.success(
                ResolvedMetadata(
                    title = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_BASIC,
                        precedence = BASIC_PRECEDENCE,
                    ) { it.title.takeIf(String::isNotBlank) },
                    synopsis = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_BASIC,
                        precedence = SYNOPSIS_PRECEDENCE,
                    ) { it.synopsis?.takeIf(String::isNotBlank) },
                    artworkUrl = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_ARTWORK,
                        precedence = ARTWORK_PRECEDENCE,
                    ) { it.coverUrl?.takeIf(String::isNotBlank) },
                    status = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_EDITORIAL,
                        precedence = EDITORIAL_PRECEDENCE,
                    ) {
                        it.status
                            .takeUnless { status -> status == CatalogItemStatus.UNKNOWN }
                            ?.name
                    },
                    format = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_EDITORIAL,
                        precedence = EDITORIAL_PRECEDENCE,
                    ) {
                        it.format
                            .takeUnless { format -> format == CatalogItemFormat.UNKNOWN }
                            ?.name
                    },
                    editorialChapterCount = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_EDITORIAL,
                        precedence = EDITORIAL_PRECEDENCE,
                    ) { it.chapterCount?.takeIf { count -> count > 0 } },
                    rating = select(
                        candidates = candidates,
                        capability = IntegrationCapability.RATINGS,
                        precedence = RATINGS_PRECEDENCE,
                    ) { it.score?.value },
                    genres = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_BASIC,
                        precedence = BASIC_PRECEDENCE,
                    ) { it.genres.takeIf(List<String>::isNotEmpty) },
                    externalIds = identities.associate { identity ->
                        IntegrationId(identity.provider) to identity.externalId
                    },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun MetadataProvider.fetch(identity: ExternalIdentity): Candidate? {
        val result = getDetails(identity.externalId)
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        val item = result.getOrNull() ?: return null
        return Candidate(
            providerId = integrationId,
            externalId = identity.externalId,
            item = item,
        )
    }

    private fun <T> select(
        candidates: List<Candidate>,
        capability: IntegrationCapability,
        precedence: List<String>,
        value: (CatalogItem) -> T?,
    ): ProvenancedMetadata<T>? {
        val ordered = candidates.sortedWith(
            compareBy<Candidate>(
                { candidate ->
                    precedence.indexOf(candidate.providerId.value)
                        .takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                },
                { candidate -> candidate.providerId.value },
                Candidate::externalId,
            ),
        )
        for (candidate in ordered) {
            if (!registry.isGlobalCapabilityActive(candidate.providerId, capability)) continue
            val resolved = value(candidate.item) ?: continue
            val attribution = registry.manifests()
                .firstOrNull { it.integrationId == candidate.providerId }
                ?.policyFor(capability)
                ?.attribution
            return ProvenancedMetadata(
                value = resolved,
                providerId = candidate.providerId,
                externalId = candidate.externalId,
                attribution = attribution,
            )
        }
        return null
    }

    private data class Candidate(
        val providerId: IntegrationId,
        val externalId: String,
        val item: CatalogItem,
    )

    private companion object {
        val METADATA_CAPABILITIES = listOf(
            IntegrationCapability.METADATA_BASIC,
            IntegrationCapability.METADATA_ARTWORK,
            IntegrationCapability.METADATA_EDITORIAL,
            IntegrationCapability.RATINGS,
        )

        val BASIC_PRECEDENCE = listOf("mangaupdates", "kitsu", "mal", "bangumi")
        val SYNOPSIS_PRECEDENCE = listOf("mangaupdates", "kitsu", "mal", "bangumi")
        val ARTWORK_PRECEDENCE = listOf("kitsu", "mal", "mangaupdates", "bangumi")
        val EDITORIAL_PRECEDENCE = listOf("mangaupdates", "mal", "kitsu", "bangumi")
        val RATINGS_PRECEDENCE = listOf("mal", "kitsu", "mangaupdates", "bangumi")
    }
}
