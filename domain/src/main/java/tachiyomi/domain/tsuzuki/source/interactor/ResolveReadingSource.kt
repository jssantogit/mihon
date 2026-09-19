package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
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
        canonicalTitleId: String,
        language: String,
        broaden: Boolean = false,
    ): SourceResolutionResult {
        val existingMappings = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
        val existing = existingMappings.firstOrNull { it.preferredOverride }
            ?: existingMappings.firstOrNull { it.language.equals(language, ignoreCase = true) }
            ?: existingMappings.firstOrNull()
        if (existing != null) {
            return SourceResolutionResult.Resolved(
                mapping = existing,
                reused = true,
            )
        }

        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: return SourceResolutionResult.NotFound(
                searchedSourceIds = emptyList(),
                canBroaden = false,
            )

        val preferred = getPreferredReadingSources.await(language)
        if (preferred.isEmpty()) {
            return SourceResolutionResult.NoPreferredSources(language)
        }

        val targetSourceIds = if (broaden) {
            broadenedSourceIds(language, preferred)
        } else {
            preferred.take(3).map { it.sourceId }
        }

        val searchedSourceIds = mutableListOf<Long>()
        val allCandidates = mutableListOf<ScoredSourceCandidate>()

        for ((rank, sourceId) in targetSourceIds.withIndex()) {
            searchedSourceIds += sourceId
            val searchResult = try {
                readingSourceGateway.search(sourceId, canonicalTitle.displayTitle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                continue
            }

            val failure = searchResult.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                continue
            }

            val sourceCandidates = searchResult.getOrThrow()
                .map { candidate ->
                    ScoredSourceCandidate(
                        candidate = candidate,
                        confidence = scoreSourceTitleMatch(
                            targetTitle = canonicalTitle.displayTitle,
                            candidateTitle = candidate.title,
                        ),
                        sourcePreferenceRank = rank,
                    )
                }
                .sortedByDescending { it.confidence }

            allCandidates += sourceCandidates

            val best = sourceCandidates.firstOrNull()
            if (best != null && best.confidence >= 0.97) {
                val second = sourceCandidates.getOrNull(1)
                val unambiguous = second == null || best.confidence - second.confidence > 0.08
                if (unambiguous) {
                    val confirmed = try {
                        confirmSourceMapping.execute(
                            canonicalTitleId = canonicalTitleId,
                            candidate = best.candidate,
                            matchConfidence = best.confidence,
                            verifiedByUser = false,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }

                    when (confirmed) {
                        is SourceResolutionResult.Resolved -> return confirmed
                        is SourceResolutionResult.Conflict -> return confirmed
                        else -> Unit
                    }
                }
            }
        }

        val confirmationCandidates = allCandidates
            .filter { it.confidence >= 0.70 }
            .sortedWith(
                compareBy<ScoredSourceCandidate> { it.sourcePreferenceRank }
                    .thenByDescending { it.confidence },
            )
            .take(5)

        if (confirmationCandidates.isNotEmpty()) {
            return SourceResolutionResult.NeedsConfirmation(confirmationCandidates)
        }

        return SourceResolutionResult.NotFound(
            searchedSourceIds = searchedSourceIds,
            canBroaden = !broaden && canBroaden(
                language = language,
                preferred = preferred,
                searchedSourceIds = searchedSourceIds,
            ),
        )
    }

    private suspend fun broadenedSourceIds(
        language: String,
        preferred: List<ReadingSourcePreference>,
    ): List<Long> {
        val preferredIds = preferred.map { it.sourceId }
        val installed = try {
            readingSourceGateway.listInstalled(language)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        return (preferredIds + installed.map { it.sourceId })
            .distinct()
    }

    private suspend fun canBroaden(
        language: String,
        preferred: List<ReadingSourcePreference>,
        searchedSourceIds: List<Long>,
    ): Boolean {
        if (preferred.any { it.sourceId !in searchedSourceIds }) {
            return true
        }

        val installed = try {
            readingSourceGateway.listInstalled(language)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return false
        }
        return installed.any { it.sourceId !in searchedSourceIds }
    }
}
