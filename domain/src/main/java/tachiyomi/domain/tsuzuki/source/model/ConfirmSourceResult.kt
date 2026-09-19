package tachiyomi.domain.tsuzuki.source.model

import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

sealed interface ConfirmSourceResult {
    data class Success(val mapping: SourceTitleMapping) : ConfirmSourceResult
    data class Conflict(val existingCanonicalTitleId: String) : ConfirmSourceResult
    data class Failure(val cause: Throwable) : ConfirmSourceResult

    fun getOrNull(): SourceTitleMapping? = (this as? Success)?.mapping

    fun getOrThrow(): SourceTitleMapping = when (this) {
        is Success -> mapping
        is Conflict -> throw SourceMappingConflictException(existingCanonicalTitleId)
        is Failure -> throw cause
    }
}

class SourceMappingConflictException(val existingCanonicalTitleId: String) :
    IllegalStateException("Source already mapped to canonical title $existingCanonicalTitleId")
