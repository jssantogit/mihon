package tachiyomi.domain.tsuzuki.integration.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedRating
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import kotlin.time.Clock
import kotlin.time.TimeSource

@Inject
class ResolveCanonicalMetadata(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val registry: IntegrationRegistry,
    private val titleArtworkRepository: TitleArtworkRepository,
    private val diagnosticRecorder: StructuredDiagnosticRecorder,
) {

    suspend fun execute(canonicalTitleId: String): Result<ResolvedMetadata> {
        val trace = DiagnosticTrace.start(
            recorder = diagnosticRecorder,
            workflow = DiagnosticWorkflow.METADATA_RESOLUTION,
            canonicalTitleId = canonicalTitleId,
            subsystem = DiagnosticSubsystem.METADATA,
        )
        val started = TimeSource.Monotonic.markNow()
        trace.event(
            subsystem = DiagnosticSubsystem.METADATA,
            name = DiagnosticEventName.METADATA_RESOLVE_STARTED,
            stage = DiagnosticStage.RESOLVE,
            outcome = DiagnosticOutcome.STARTED,
        )
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
                trace.event(
                    subsystem = DiagnosticSubsystem.METADATA,
                    name = DiagnosticEventName.METADATA_RESOLVE_COMPLETED,
                    stage = DiagnosticStage.COMPLETE,
                    outcome = DiagnosticOutcome.EMPTY,
                    durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                )
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

            candidates.forEach { candidate ->
                trace.child().event(
                    subsystem = DiagnosticSubsystem.METADATA,
                    name = DiagnosticEventName.METADATA_PROVIDER_RESULT,
                    stage = DiagnosticStage.READ,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    attributes = mapOf(
                        DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(candidate.providerId.value),
                        DiagnosticAttribute.COVER_PRESENT to
                            DiagnosticAttributeValue.Flag(!candidate.item.coverUrl.isNullOrBlank()),
                    ),
                )
            }

            persistArtwork(canonicalTitleId, candidates)

            val ratings = resolveRatings(
                candidates = candidates,
                identities = identities,
                trace = trace,
            )

            val resolved = ResolvedMetadata(
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
            )
            trace.event(
                subsystem = DiagnosticSubsystem.METADATA,
                name = DiagnosticEventName.METADATA_RESOLVE_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.SUCCEEDED,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                attributes = mapOf(
                    DiagnosticAttribute.CANDIDATE_COUNT to DiagnosticAttributeValue.Number(candidates.size.toLong()),
                    DiagnosticAttribute.COVER_PRESENT to
                        DiagnosticAttributeValue.Flag(!resolved.artworkUrl?.value.isNullOrBlank()),
                ),
            )
            Result.success(resolved)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            trace.event(
                subsystem = DiagnosticSubsystem.METADATA,
                name = DiagnosticEventName.METADATA_RESOLVE_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.WARN,
                durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
            )
            Result.failure(error)
        }
    }

    private suspend fun persistArtwork(
        canonicalTitleId: String,
        candidates: List<Candidate>,
    ) {
        val repository = titleArtworkRepository
        val now = Clock.System.now().toEpochMilliseconds()
        candidates.forEach { candidate ->
            if (
                !registry.isGlobalCapabilityActive(
                    candidate.providerId,
                    IntegrationCapability.METADATA_ARTWORK,
                )
            ) {
                return@forEach
            }
            val coverUrl = candidate.item.coverUrl?.takeIf(String::isNotBlank)
            val bannerUrl = candidate.item.bannerUrl?.takeIf(String::isNotBlank)
            if (coverUrl == null && bannerUrl == null) return@forEach

            try {
                repository.upsert(
                    TitleArtworkObservation(
                        canonicalTitleId = canonicalTitleId,
                        provider = candidate.providerId.value,
                        coverUrl = coverUrl,
                        bannerUrl = bannerUrl,
                        updatedAt = now,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Artwork persistence is a cache and must never block metadata resolution.
            }
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

    private suspend fun resolveRatings(
        candidates: List<Candidate>,
        identities: List<ExternalIdentity>,
        trace: DiagnosticTrace,
    ): List<ProvenancedMetadata<ResolvedRating>> = coroutineScope {
        val exactRatings = selectRatings(candidates)
        exactRatings.forEach { rating ->
            trace.child().event(
                subsystem = DiagnosticSubsystem.METADATA,
                name = DiagnosticEventName.RATING_PROVIDER_RESULT,
                stage = DiagnosticStage.READ,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf(
                    DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(rating.providerId.value),
                    DiagnosticAttribute.IDENTITY_VERIFIED to DiagnosticAttributeValue.Flag(true),
                    DiagnosticAttribute.RATING_PRESENT to DiagnosticAttributeValue.Flag(true),
                ),
            )
        }
        val existingProviderIds = exactRatings.map { it.providerId.value }.toSet()
        val seed = buildRatingSeed(candidates, identities)
            ?: return@coroutineScope exactRatings

        val supplemental = registry.ratingsProviders()
            .filterNot { provider -> provider.integrationId.value in existingProviderIds }
            .map { provider ->
                async {
                    val result = provider.ratingFor(seed)
                    val error = result.exceptionOrNull()
                    if (error is CancellationException) throw error
                    val match = result.getOrNull()
                    trace.child().event(
                        subsystem = DiagnosticSubsystem.METADATA,
                        name = DiagnosticEventName.RATING_PROVIDER_RESULT,
                        stage = DiagnosticStage.MATCH,
                        outcome = when {
                            result.isFailure -> DiagnosticOutcome.FAILED
                            match == null -> DiagnosticOutcome.EMPTY
                            else -> DiagnosticOutcome.SUCCEEDED
                        },
                        severity = if (result.isFailure) {
                            DiagnosticSeverity.WARN
                        } else {
                            DiagnosticSeverity.INFO
                        },
                        attributes = mapOf(
                            DiagnosticAttribute.PROVIDER_ID to
                                DiagnosticAttributeValue.Text(provider.integrationId.value),
                            DiagnosticAttribute.IDENTITY_VERIFIED to
                                DiagnosticAttributeValue.Flag(match?.verifiedIdentity == true),
                            DiagnosticAttribute.RATING_PRESENT to
                                DiagnosticAttributeValue.Flag(match != null),
                        ),
                    )
                    match?.let { match ->
                        val attribution = registry.manifests()
                            .firstOrNull { it.integrationId == provider.integrationId }
                            ?.policyFor(IntegrationCapability.RATINGS)
                            ?.attribution
                        ProvenancedMetadata(
                            value = ResolvedRating(
                                value = match.rating.value,
                                maxValue = match.rating.scaleMax,
                            ),
                            providerId = provider.integrationId,
                            externalId = match.externalId,
                            attribution = attribution,
                        )
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        (exactRatings + supplemental)
            .distinctBy { it.providerId }
            .sortedWith(
                compareBy<ProvenancedMetadata<ResolvedRating>>(
                    { rating ->
                        RATINGS_PRECEDENCE.indexOf(rating.providerId.value)
                            .takeIf { index -> index >= 0 }
                            ?: Int.MAX_VALUE
                    },
                    { rating -> rating.providerId.value },
                ),
            )
    }

    private fun buildRatingSeed(
        candidates: List<Candidate>,
        identities: List<ExternalIdentity>,
    ): CatalogItem? {
        val ordered = candidates.sortedWith(
            compareBy<Candidate>(
                { candidate ->
                    RATINGS_PRECEDENCE.indexOf(candidate.providerId.value)
                        .takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                },
                { candidate -> candidate.providerId.value },
            ),
        )
        val base = ordered.firstOrNull()?.item ?: return null
        val titles = buildMap {
            ordered.forEach { candidate ->
                put("${candidate.providerId.value}:primary", candidate.item.title)
                candidate.item.titles.forEach { (key, value) ->
                    put("${candidate.providerId.value}:$key", value)
                }
            }
        }
        val externalIds = buildMap {
            ordered.forEach { candidate ->
                putAll(candidate.item.externalIds)
            }
            identities.forEach { identity ->
                put(identity.provider, identity.externalId)
            }
        }
        val authors = ordered.flatMap { it.item.authors }.filter(String::isNotBlank).distinct()
        val artists = ordered.flatMap { it.item.artists }.filter(String::isNotBlank).distinct()
        val startDate = ordered.firstNotNullOfOrNull { it.item.startDate?.takeIf(String::isNotBlank) }
        val endDate = ordered.firstNotNullOfOrNull { it.item.endDate?.takeIf(String::isNotBlank) }
        val format = ordered
            .map { it.item.format }
            .firstOrNull { it != CatalogItemFormat.UNKNOWN }
            ?: CatalogItemFormat.UNKNOWN

        return base.copy(
            titles = titles,
            externalIds = externalIds,
            authors = authors,
            artists = artists,
            startDate = startDate,
            endDate = endDate,
            format = format,
        )
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
