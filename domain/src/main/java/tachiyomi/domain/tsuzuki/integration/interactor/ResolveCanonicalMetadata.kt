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
import tachiyomi.domain.tsuzuki.integration.model.ResolvedRating
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
                .flatMap { capability -> registry.metadataProviders(capability) }
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

            val ratings = selectRatings(candidates)

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
                    rating = ratings.firstOrNull()?.let { rating ->
                        ProvenancedMetadata(
                            value = rating.value.value,
                            providerId = rating.providerId,
                            externalId = rating.externalId,
                            attribution = rating.attribution,
                        )
                    },
                    ratingDetails = ratings.firstOrNull(),
                    ratings = ratings,
                    authors = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_STAFF,
                        precedence = STAFF_PRECEDENCE,
                    ) { it.authors.takeIf { authors -> authors.isNotEmpty() } },
                    artists = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_STAFF,
                        precedence = STAFF_PRECEDENCE,
                    ) { it.artists.takeIf { artists -> artists.isNotEmpty() } },
                    genres = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_BASIC,
                        precedence = BASIC_PRECEDENCE,
                    ) { it.genres.takeIf { genres -> genres.isNotEmpty() } },
                    tags = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_BASIC,
                        precedence = BASIC_PRECEDENCE,
                    ) { it.tags.takeIf { tags -> tags.isNotEmpty() } },
                    startDate = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_EDITORIAL,
                        precedence = EDITORIAL_PRECEDENCE,
                    ) { it.startDate?.takeIf(String::isNotBlank) },
                    endDate = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_EDITORIAL,
                        precedence = EDITORIAL_PRECEDENCE,
                    ) { it.endDate?.takeIf(String::isNotBlank) },
                    editorialVolumeCount = select(
                        candidates = candidates,
                        capability = IntegrationCapability.METADATA_EDITORIAL,
                        precedence = EDITORIAL_PRECEDENCE,
                    ) { it.volumeCount?.takeIf { count -> count > 0 } },
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

    private fun selectRatings(
        candidates: List<Candidate>,
    ): List<ProvenancedMetadata<ResolvedRating>> {
        val ordered = candidates.sortedWith(
            compareBy<Candidate>(
                { candidate ->
                    RATINGS_PRECEDENCE.indexOf(candidate.providerId.value)
                        .takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                },
                { candidate -> candidate.providerId.value },
                Candidate::externalId,
            ),
        )

        return ordered
            .asSequence()
            .filter { candidate ->
                registry.isGlobalCapabilityActive(
                    candidate.providerId,
                    IntegrationCapability.RATINGS,
                )
            }
            .mapNotNull { candidate ->
                val score = candidate.item.score ?: return@mapNotNull null
                val attribution = registry.manifests()
                    .firstOrNull { it.integrationId == candidate.providerId }
                    ?.policyFor(IntegrationCapability.RATINGS)
                    ?.attribution
                ProvenancedMetadata(
                    value = ResolvedRating(
                        value = score.value,
                        maxValue = score.maxValue,
                        voteCount = score.voteCount,
                    ),
                    providerId = candidate.providerId,
                    externalId = candidate.externalId,
                    attribution = attribution,
                )
            }
            .distinctBy { it.providerId }
            .toList()
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
            IntegrationCapability.METADATA_STAFF,
            IntegrationCapability.RATINGS,
        )

        val BASIC_PRECEDENCE = listOf("mangaupdates", "kitsu", "mal", "bangumi")
        val SYNOPSIS_PRECEDENCE = listOf("mangaupdates", "kitsu", "mal", "bangumi")
        val ARTWORK_PRECEDENCE = listOf("kitsu", "mal", "mangaupdates", "bangumi")
        val EDITORIAL_PRECEDENCE = listOf("mangaupdates", "mal", "kitsu", "bangumi")
        val STAFF_PRECEDENCE = listOf("mangaupdates", "mal", "kitsu", "bangumi")
        val RATINGS_PRECEDENCE = listOf("mal", "kitsu", "mangaupdates", "bangumi")
    }
}
