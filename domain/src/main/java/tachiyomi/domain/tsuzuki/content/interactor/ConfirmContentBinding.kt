package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.util.UUID
import kotlin.time.Clock

/**
 * Explicit user confirmation for an ambiguous title result. Unlike automatic matching, this
 * action only runs after the user chooses a concrete candidate returned by a live Add-on search.
 *
 * Does not merge titles, migrate a Mihon entry, or mutate canonical reading progress.
 */
@Inject
class ConfirmContentBinding(
    private val contentBindingRepository: ContentBindingRepository,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val addonRepository: AddonRepository,
    private val readingSourceGateway: ReadingSourceGateway,
    private val scoreSourceTitleMatch: ScoreSourceTitleMatch,
) {
    suspend fun execute(
        canonicalTitleId: String,
        addonId: AddonId,
        selected: ScoredSourceCandidate,
    ): Result<ContentBinding> {
        return try {
            val addon = addonRepository.snapshot()
                .firstOrNull { it.id == addonId && it.enabled }
                ?: throw IllegalStateException("Reading Add-on is not enabled")
            val candidate = selected.candidate
            require(candidate.sourceId in addon.mihonSourceIds) {
                "The selected internal source is not enabled for this Add-on"
            }
            require(candidate.sourceUrl.isNotBlank()) { "Selected candidate has no source URL" }
            val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
                ?: throw IllegalStateException("Canonical title no longer exists")
            val confidence = scoreSourceTitleMatch(
                targetTitle = canonicalTitle.displayTitle,
                candidateTitle = candidate.title,
            )
            // Selection originates in the ambiguity prompt, which only exposes plausible matches.
            // Rechecking here prevents accidental use of an unrelated candidate.
            require(confidence >= MIN_CONFIRMABLE_CONFIDENCE) {
                "Selected title does not meet the confirmation threshold"
            }
            val materialized = readingSourceGateway.materialize(candidate).getOrThrow()
            require(
                materialized.sourceId == candidate.sourceId &&
                    materialized.sourceUrl == candidate.sourceUrl &&
                    materialized.runtimePayload.isNotEmpty()
            ) { "Materialized source does not match the explicitly selected candidate" }

            val existing = contentBindingRepository.getByTitle(canonicalTitleId)
                .firstOrNull {
                    it.addonId == addonId && it.providerTitleKey == materialized.providerTitleKey
                }
            val now = Clock.System.now().toEpochMilliseconds()
            val binding = ContentBinding(
                id = existing?.id ?: UUID.randomUUID().toString(),
                canonicalTitleId = canonicalTitleId,
                addonId = addonId,
                providerTitleKey = materialized.providerTitleKey,
                matchConfidence = confidence,
                verifiedByUser = true,
                availability = ContentBindingAvailability.AVAILABLE,
                runtimePayload = materialized.runtimePayload,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            contentBindingRepository.upsert(binding)
            Result.success(binding)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private companion object {
        const val MIN_CONFIRMABLE_CONFIDENCE = 0.70
    }
}
