package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ConfirmSourceResult
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

@Inject
class ResolveReadingSource(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val getPreferredReadingSources: GetPreferredReadingSources,
    private val readingSourceGateway: ReadingSourceGateway,
    private val scoreSourceTitleMatch: ScoreSourceTitleMatch,
    private val confirmSourceMapping: ConfirmSourceMapping,
) {

    suspend fun execute(
        canonicalTitleId: Long,
        language: String,
        broaden: Boolean = false,
    ): SourceResolutionResult {
        return execute(canonicalTitleId.toString(), language, broaden)
    }

    suspend fun execute(
        canonicalTitleId: String,
        language: String,
        broaden: Boolean = false,
    ): SourceResolutionResult {
        val existingMappings = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
        val existing = existingMappings.firstOrNull { it.language.equals(language, ignoreCase = true) }
            ?: existingMappings.firstOrNull { it.preferredOverride }
            ?: existingMappings.firstOrNull()
        if (existing != null) {
            return SourceResolutionResult.ExistingMapping(mapping = existing, reused = true)
        }

        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: return SourceResolutionResult.NotFound
        val query = canonicalTitle.displayTitle

        val preferred = getPreferredReadingSources.await(language)
        if (preferred.isEmpty() && !broaden) {
            return SourceResolutionResult.NoPreferredSources
        }

        val targetSourceIds = if (!broaden) {
            preferred.take(3).map { it.sourceId }
        } else {
            val prefIds = preferred.map { it.sourceId }
            val available = try {
                readingSourceGateway.getAvailableSources(language)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            val additionalIds = available.map { it.sourceId }.filter { it !in prefIds }
            prefIds + additionalIds
        }

        if (targetSourceIds.isEmpty()) {
            return if (preferred.isEmpty()) {
                SourceResolutionResult.NoPreferredSources
            } else {
                SourceResolutionResult.NotFound
            }
        }

        val allScoredCandidates = mutableListOf<ScoredSourceCandidate>()
        val candidatesBySource = mutableMapOf<Long, MutableList<ScoredSourceCandidate>>()

        for ((index, sourceId) in targetSourceIds.withIndex()) {
            try {
                val searchResult = readingSourceGateway.searchSource(sourceId, query)
                if (searchResult.isSuccess) {
                    val candidates = searchResult.getOrThrow()
                    for (candidate in candidates) {
                        val score = scoreSourceTitleMatch(query, candidate.title)
                        val scored = ScoredSourceCandidate(
                            candidate = candidate,
                            confidence = score,
                            sourcePreferenceRank = index,
                        )
                        allScoredCandidates.add(scored)
                        candidatesBySource.getOrPut(sourceId) { mutableListOf() }.add(scored)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Source failures do not abort healthy attempts
            }
        }

        for (sourceId in targetSourceIds) {
            val sourceCandidates = candidatesBySource[sourceId]?.sortedByDescending { it.confidence } ?: continue
            if (sourceCandidates.isEmpty()) continue

            val best = sourceCandidates[0]
            if (best.confidence >= 0.97) {
                val second = sourceCandidates.getOrNull(1)
                val isUnambiguous = second == null || (best.confidence - second.confidence > 0.08)
                if (isUnambiguous) {
                    val confirmResult = confirmSourceMapping.execute(
                        canonicalTitleId = canonicalTitleId,
                        candidate = best.candidate,
                        language = language,
                        matchConfidence = best.confidence,
                        verifiedByUser = false,
                    )
                    when (confirmResult) {
                        is ConfirmSourceResult.Success -> {
                            return SourceResolutionResult.AutoAccepted(
                                mapping = confirmResult.mapping,
                                candidate = best,
                            )
                        }
                        is ConfirmSourceResult.Conflict -> {
                            return SourceResolutionResult.Conflict(confirmResult.existingCanonicalTitleId)
                        }
                        is ConfirmSourceResult.Failure -> {
                            // If confirm fails, fall through to NeedsConfirmation
                        }
                    }
                }
            }
        }

        val candidatesForConfirmation = allScoredCandidates
            .filter { it.confidence >= 0.70 }
            .sortedWith(
                compareBy<ScoredSourceCandidate> { it.sourcePreferenceRank }
                    .thenByDescending { it.confidence },
            )
            .take(5)

        if (candidatesForConfirmation.isNotEmpty()) {
            return SourceResolutionResult.NeedsConfirmation(
                canonicalTitleId = canonicalTitleId,
                candidates = candidatesForConfirmation,
            )
        }

        return SourceResolutionResult.NotFound
    }
}
