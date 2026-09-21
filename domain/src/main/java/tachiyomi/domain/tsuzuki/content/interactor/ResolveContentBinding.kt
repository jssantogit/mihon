package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
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

class ResolveContentBinding internal constructor(
    private val contentBindingRepository: ContentBindingRepository,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val addonRepository: AddonRepository,
    private val readingSourceGateway: ReadingSourceGateway,
    private val scoreSourceTitleMatch: ScoreSourceTitleMatch,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        contentBindingRepository: ContentBindingRepository,
        canonicalTitleRepository: CanonicalTitleRepository,
        addonRepository: AddonRepository,
        readingSourceGateway: ReadingSourceGateway,
        scoreSourceTitleMatch: ScoreSourceTitleMatch,
    ) : this(
        contentBindingRepository = contentBindingRepository,
        canonicalTitleRepository = canonicalTitleRepository,
        addonRepository = addonRepository,
        readingSourceGateway = readingSourceGateway,
        scoreSourceTitleMatch = scoreSourceTitleMatch,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        canonicalTitleId: String,
        addonId: AddonId,
    ): Result<ContentBinding> {
        return try {
            Result.success(resolve(canonicalTitleId, addonId))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun resolve(
        canonicalTitleId: String,
        addonId: AddonId,
    ): ContentBinding {
        val addon = requireExecutableAddon(addonId)
        val existing = contentBindingRepository.get(canonicalTitleId, addonId)
        if (existing != null && existing.availability != ContentBindingAvailability.UNAVAILABLE) {
            return existing
        }

        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: throw ContentBindingNotFoundException(
                "Canonical title $canonicalTitleId does not exist",
            )

        val candidates = addon.mihonSourceIds
            .flatMapIndexed { sourceRank, sourceId ->
                val results = try {
                    readingSourceGateway.search(sourceId, canonicalTitle.displayTitle).getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        return@flatMapIndexed emptyList()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    return@flatMapIndexed emptyList()
                }

                results.map { candidate ->
                    ScoredSourceCandidate(
                        candidate = candidate,
                        confidence = scoreSourceTitleMatch(
                            targetTitle = canonicalTitle.displayTitle,
                            candidateTitle = candidate.title,
                        ),
                        sourcePreferenceRank = sourceRank,
                    )
                }
            }
            .sortedWith(
                compareByDescending<ScoredSourceCandidate> { it.confidence }
                    .thenBy { it.sourcePreferenceRank }
                    .thenBy { it.candidate.sourceId }
                    .thenBy { it.candidate.sourceUrl },
            )

        val best = candidates.firstOrNull()
            ?: throw ContentBindingNotFoundException(
                "No title candidate found for " + addonId.value,
            )
        val second = candidates.getOrNull(1)
        val highConfidence = best.confidence >= AUTO_MATCH_THRESHOLD
        val unambiguous = second == null || best.confidence - second.confidence > AUTO_MATCH_MARGIN

        if (!highConfidence || !unambiguous) {
            val confirmationCandidates = candidates
                .filter { it.confidence >= CONFIRMATION_THRESHOLD }
                .take(MAX_CONFIRMATION_CANDIDATES)
            if (confirmationCandidates.isNotEmpty()) {
                throw ContentBindingConfirmationRequiredException(confirmationCandidates)
            }
            throw ContentBindingNotFoundException(
                "No sufficiently confident title candidate found for " + addonId.value,
            )
        }

        val materialized = readingSourceGateway.materialize(best.candidate).getOrThrow()
        require(materialized.sourceId == best.candidate.sourceId) {
            "Materialized source does not match selected candidate"
        }
        require(materialized.sourceUrl == best.candidate.sourceUrl) {
            "Materialized URL does not match selected candidate"
        }
        require(materialized.runtimePayload.isNotEmpty()) {
            "Materialized binding must include provider runtime payload"
        }

        val now = clock()
        val binding = ContentBinding(
            id = existing?.id ?: idFactory(),
            canonicalTitleId = canonicalTitleId,
            addonId = addonId,
            providerTitleKey = materialized.providerTitleKey,
            matchConfidence = best.confidence,
            verifiedByUser = existing?.verifiedByUser ?: false,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = materialized.runtimePayload,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        contentBindingRepository.upsert(binding)
        return binding
    }

    private suspend fun requireExecutableAddon(addonId: AddonId): InstalledAddon {
        return addonRepository.snapshot()
            .firstOrNull { it.id == addonId && it.enabled }
            ?: throw ContentBindingNotFoundException(
                "Add-on " + addonId.value + " is not installed and enabled",
            )
    }

    private companion object {
        const val AUTO_MATCH_THRESHOLD = 0.97
        const val AUTO_MATCH_MARGIN = 0.08
        const val CONFIRMATION_THRESHOLD = 0.70
        const val MAX_CONFIRMATION_CANDIDATES = 5
    }
}

class ContentBindingConfirmationRequiredException(
    val candidates: List<ScoredSourceCandidate>,
) : IllegalStateException("Content binding requires user confirmation")

class ContentBindingNotFoundException(
    message: String,
) : IllegalStateException(message)
