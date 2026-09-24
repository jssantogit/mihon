package tachiyomi.domain.tsuzuki.content.interactor

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate

/** Parameters for one bounded initial or explicitly broadened Add-on search. */
data class ContentBindingSearchRequest(
    val canonicalTitleId: String,
    val addonId: AddonId,
    val preferredLanguages: List<String> = emptyList(),
    val mode: ContentBindingSearchMode = ContentBindingSearchMode.INITIAL,
    val alreadyQueriedSourceIds: Set<Long> = emptySet(),
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** Cooperative per-source deadline; blocking Java extension calls may outlive it. */
    val sourceTimeoutMillis: Long = DEFAULT_SOURCE_TIMEOUT_MILLIS,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) {
            "Search batch size must be between 1 and $MAX_BATCH_SIZE"
        }
        require(sourceTimeoutMillis in 1..MAX_SOURCE_TIMEOUT_MILLIS) {
            "Source timeout must be between 1 and $MAX_SOURCE_TIMEOUT_MILLIS milliseconds"
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 3
        const val MAX_BATCH_SIZE = 5
        const val DEFAULT_SOURCE_TIMEOUT_MILLIS = 25_000L
        const val MAX_SOURCE_TIMEOUT_MILLIS = 30_000L
    }
}

enum class ContentBindingSearchMode {
    INITIAL,
    BROADEN,
}

/** Closed per-source outcomes; EMPTY is reserved for a completed, genuinely empty search. */
enum class ContentBindingSourceOutcome {
    BOUND,
    CONFIRMATION_REQUIRED,
    EMPTY,
    NO_MATCH,
    FAILURE,
}

enum class ContentBindingSearchFailureStage {
    ADDON_DISCOVERY,
    SEARCH,
    MATERIALIZATION,
    PERSISTENCE,
}

enum class ContentBindingSearchFailureKind {
    ADDON_NOT_INSTALLED,
    ADDON_DISABLED,
    NO_ENABLED_SOURCES,
    SOURCE_DISABLED,
    SOURCE_UNAVAILABLE,
    HTTP_RESPONSE,
    NETWORK_FAILURE,
    TIMEOUT,
    CAPTCHA_REQUIRED,
    MALFORMED_RESPONSE,
    EXTENSION_FAILURE,
    INDETERMINATE,
}

data class ContentBindingSearchFailure(
    val stage: ContentBindingSearchFailureStage,
    val kind: ContentBindingSearchFailureKind,
    val httpStatus: Int? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
    }
}

/**
 * Cold, per-collection progress. Provider candidates are exposed only when they passed the
 * existing confirmation threshold; a candidate is never treated as a chapter/content option.
 */
sealed interface ContentBindingSearchProgress {
    data class BindingReused(
        val bindings: List<ContentBinding>,
    ) : ContentBindingSearchProgress

    data class SourceCompleted(
        val sourceId: Long?,
        val language: String?,
        val outcome: ContentBindingSourceOutcome,
        val candidates: List<ScoredSourceCandidate> = emptyList(),
        val bindings: List<ContentBinding> = emptyList(),
        val failure: ContentBindingSearchFailure? = null,
    ) : ContentBindingSearchProgress

    data class Completed(
        /** IDs queried in this collection only, in the selected preferred/source order. */
        val queriedSourceIds: List<Long>,
        val remainingSourceCount: Int,
    ) : ContentBindingSearchProgress
}

internal fun ReadingSourceFailureKind.toContentBindingFailureKind(): ContentBindingSearchFailureKind =
    when (this) {
        ReadingSourceFailureKind.SOURCE_DISABLED -> ContentBindingSearchFailureKind.SOURCE_DISABLED
        ReadingSourceFailureKind.SOURCE_UNAVAILABLE -> ContentBindingSearchFailureKind.SOURCE_UNAVAILABLE
        ReadingSourceFailureKind.HTTP_RESPONSE -> ContentBindingSearchFailureKind.HTTP_RESPONSE
        ReadingSourceFailureKind.NETWORK_FAILURE -> ContentBindingSearchFailureKind.NETWORK_FAILURE
        ReadingSourceFailureKind.TIMEOUT -> ContentBindingSearchFailureKind.TIMEOUT
        ReadingSourceFailureKind.CAPTCHA_REQUIRED -> ContentBindingSearchFailureKind.CAPTCHA_REQUIRED
        ReadingSourceFailureKind.MALFORMED_RESPONSE -> ContentBindingSearchFailureKind.MALFORMED_RESPONSE
        ReadingSourceFailureKind.EXTENSION_FAILURE -> ContentBindingSearchFailureKind.EXTENSION_FAILURE
        ReadingSourceFailureKind.INDETERMINATE -> ContentBindingSearchFailureKind.INDETERMINATE
    }
