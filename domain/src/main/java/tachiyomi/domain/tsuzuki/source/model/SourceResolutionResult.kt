package tachiyomi.domain.tsuzuki.source.model

import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

sealed interface SourceResolutionResult {
    data class ExistingMapping(
        val mapping: SourceTitleMapping,
        val reused: Boolean = true,
    ) : SourceResolutionResult

    data class AutoAccepted(
        val mapping: SourceTitleMapping,
        val candidate: ScoredSourceCandidate,
    ) : SourceResolutionResult

    data class NeedsConfirmation(
        val canonicalTitleId: String,
        val candidates: List<ScoredSourceCandidate>,
    ) : SourceResolutionResult {
        constructor(
            canonicalTitleId: Long,
            candidates: List<ScoredSourceCandidate>,
        ) : this(canonicalTitleId.toString(), candidates)
    }

    data object NotFound : SourceResolutionResult
    data object NoPreferredSources : SourceResolutionResult
    data class Conflict(val existingCanonicalTitleId: String) : SourceResolutionResult
}
