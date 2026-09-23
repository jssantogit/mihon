package tachiyomi.domain.tsuzuki.source.model

import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

sealed interface SourceResolutionResult {
    data class Resolved(
        val mapping: SourceTitleMapping,
        val reused: Boolean,
    ) : SourceResolutionResult

    data class NeedsConfirmation(
        val candidates: List<ScoredSourceCandidate>,
    ) : SourceResolutionResult

    data class NotFound(
        val searchedSourceIds: List<Long>,
        val canBroaden: Boolean,
    ) : SourceResolutionResult

    data class NoPreferredSources(
        val language: String,
    ) : SourceResolutionResult

    data class Conflict(
        val existingCanonicalTitleId: String,
    ) : SourceResolutionResult
}
