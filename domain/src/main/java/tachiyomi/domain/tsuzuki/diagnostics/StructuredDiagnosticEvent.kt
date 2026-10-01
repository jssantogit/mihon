package tachiyomi.domain.tsuzuki.diagnostics

/** Versioned, provider-neutral diagnostics contract. Free-form messages and throwables are absent. */
data class StructuredDiagnosticEvent(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val subsystem: DiagnosticSubsystem,
    val name: DiagnosticEventName,
    val sessionId: String,
    val operationId: String?,
    val workflowId: String? = null,
    val parentOperationId: String? = null,
    val workflow: DiagnosticWorkflow? = null,
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val durationMillis: Long? = null,
    val attempt: Int? = null,
    val attributes: Map<String, DiagnosticAttributeValue> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class DiagnosticSubsystem {
    APP,
    NETWORK,
    INTEGRATION,
    METADATA,
    ARTWORK,
    IMAGE,
    LIBRARY,
    SOURCE,
    CONTENT,
    CHAPTER,
    READER,
    DOWNLOAD,
    DATABASE,
    DIAGNOSTICS,
}

enum class DiagnosticWorkflow {
    OPEN_TITLE,
    CONTINUE_READING,
    LIBRARY_SYNC,
    SOURCE_RESOLUTION,
    CONTENT_RESOLUTION,
    CHAPTER_REFRESH,
    READER_OPEN,
    READER_SOURCE_SWITCH,
    DOWNLOAD_CHAPTER,
    ARTWORK_RESOLUTION,
    METADATA_RESOLUTION,
    DIAGNOSTIC_CAPTURE,
}

enum class DiagnosticEventName {
    WORKFLOW_STARTED,
    WORKFLOW_COMPLETED,

    SOURCE_RESOLVE_STARTED,
    SOURCE_RESOLVE_MAPPING_REUSED,
    SOURCE_RESOLVE_PREFERRED_SOURCES,
    SOURCE_SEARCH_STARTED,
    SOURCE_SEARCH_COMPLETED,
    SOURCE_SEARCH_FAILED,
    SOURCE_MATCH_EVALUATED,
    SOURCE_MAPPING_CONFIRMATION_FAILED,
    SOURCE_RESOLVE_COMPLETED,

    ARTWORK_RESOLVE_STARTED,
    ARTWORK_OBSERVATIONS_READ,
    ARTWORK_PROVIDER_SELECTED,
    ARTWORK_CANDIDATES_BUILT,
    ARTWORK_LOAD_ATTEMPT,
    ARTWORK_LOAD_COMPLETED,

    METADATA_RESOLVE_STARTED,
    METADATA_PROVIDER_RESULT,
    RATING_PROVIDER_RESULT,
    TSUZUKI_RATING_COMPUTED,
    METADATA_RESOLVE_COMPLETED,

    LIBRARY_SYNC_STARTED,
    LIBRARY_PROVIDER_FETCHED,
    LIBRARY_SYNC_COMPLETED,

    CONTENT_BINDING_LOOKUP,
    CONTENT_BINDING_RESOLVE_STARTED,
    CONTENT_BINDING_RESOLVE_COMPLETED,

    CHAPTER_REFRESH_STARTED,
    CHAPTER_EVIDENCE_RECONCILED,
    CHAPTER_REFRESH_COMPLETED,

    READER_OPEN_STARTED,
    READER_SOURCE_SELECTED,
    READER_PAGES_READY,
    READER_OPEN_COMPLETED,
    READER_PROGRESS_RECORDED,

    DOWNLOAD_STARTED,
    DOWNLOAD_PREPARED,
    DOWNLOAD_COMPLETED,

    CACHE_LOOKUP,
    DATABASE_OPERATION,
    INVARIANT_VIOLATION,
    DIAGNOSTIC_RECORDER_HEALTH,
    CAPTURE_SESSION_STARTED,
    CAPTURE_SESSION_STOPPED,
    CRASH_CONTEXT_UPDATED,
}

enum class DiagnosticStage {
    WORKFLOW,
    RESOLVE,
    PREFERRED_SOURCES,
    SEARCH,
    MATCH,
    CONFIRMATION,
    READ,
    WRITE,
    LOOKUP,
    PERSIST,
    IMAGE_LOAD,
    RENDER,
    SYNC,
    BINDING,
    RECONCILE,
    READER,
    DOWNLOAD,
    SUMMARY,
    CAPTURE,
    CRASH_CONTEXT,
    COMPLETE,
}

enum class DiagnosticOutcome {
    STARTED,
    SUCCEEDED,
    REUSED,
    CANDIDATES,
    NEEDS_CONFIRMATION,
    TYPED_FAILURE,
    THREW,
    NOT_FOUND_NO_CANDIDATES,
    NOT_FOUND_WITH_SOURCE_FAILURES,
    NO_PREFERRED_SOURCES,
    HIT,
    MISS,
    ACCEPTED,
    REJECTED,
    PARTIAL,
    SKIPPED,
    READY,
    EMPTY,
    TIMEOUT,
    FAILED,
    CANCELLED,
}

enum class DiagnosticAttribute {
    SOURCE_ID,
    SOURCE_COUNT,
    PREFERRED_SOURCE_COUNT,
    TARGET_SOURCE_COUNT,
    CANDIDATE_COUNT,
    CANDIDATE_INDEX,
    BROADENED,
    MAPPING_REUSED,
    LANGUAGE,
    PROVIDER_ID,
    ADDON_ID,
    CANONICAL_TITLE_REF,
    MIHON_MANGA_REF,
    CONFIDENCE_SCORE,
    CONFIDENCE_BUCKET,
    AUTO_CONFIRM_ATTEMPTED,
    AMBIGUOUS,
    ERROR_CATEGORY,
    HTTP_STATUS,

    OBSERVATION_COUNT,
    ITEM_COUNT,
    ACCEPTED_COUNT,
    REJECTED_COUNT,
    PAGE_COUNT,
    PAGE_INDEX,
    BINDING_COUNT,
    CHAPTER_COUNT,
    VARIANT_COUNT,
    QUEUE_DEPTH,

    COVER_PRESENT,
    SOURCE_ARTWORK_PRESENT,
    CANONICAL_ARTWORK_PRESENT,
    REQUEST_DATA_PRESENT,
    INITIALIZED,
    COMPLETED,
    IDENTITY_VERIFIED,
    RATING_PRESENT,
    RATING_SOURCE_COUNT,
    RATING_VERIFIED_SOURCE_COUNT,
    RATING_CORROBORATED_SOURCE_COUNT,
    TSUZUKI_RATING_ENABLED,
    TSUZUKI_RATING_PRESENT,

    CANDIDATE_TYPE,
    CACHE_STATUS,
    INVARIANT_CODE,

    EVENTS_RECEIVED,
    EVENTS_SANITIZED,
    EVENTS_REJECTED,
    LOGCAT_SINK_FAILURES,
    HISTORY_SINK_FAILURES,
    HISTORY_DROPPED_EVENTS,
    EXPORT_FLUSH_TIMEOUTS,
}

sealed interface DiagnosticAttributeValue {
    data class Number(val value: Long) : DiagnosticAttributeValue

    data class Flag(val value: Boolean) : DiagnosticAttributeValue

    data class Text(val value: String) : DiagnosticAttributeValue

    data class Code(val value: DiagnosticSafeCode) : DiagnosticAttributeValue
}

/** Closed technical values only; callers cannot attach provider or exception text. */
sealed interface DiagnosticSafeCode

enum class DiagnosticErrorCategory : DiagnosticSafeCode {
    NETWORK,
    TIMEOUT,
    HTTP,
    CAPTCHA,
    SOURCE_UNAVAILABLE,
    MALFORMED_RESPONSE,
    EXTENSION,
    DATABASE,
    IMAGE,
    DIAGNOSTICS,
    UNKNOWN,
}

enum class DiagnosticConfidenceBucket : DiagnosticSafeCode {
    LOW,
    MEDIUM,
    HIGH,
}

enum class DiagnosticCandidateType : DiagnosticSafeCode {
    PROVIDER,
    SOURCE_AWARE,
    SOURCE_RAW,
    LOCAL,
}

enum class DiagnosticCacheStatus : DiagnosticSafeCode {
    HIT,
    MISS,
    STALE,
    BYPASSED,
}

enum class DiagnosticInvariantCode : DiagnosticSafeCode {
    ARTWORK_LOST_AFTER_RESOLUTION,
    CONTENT_BINDING_EXISTS_BUT_NOT_CONSUMED,
    CHAPTER_VARIANT_WITHOUT_EVIDENCE,
    READER_OPEN_WITHOUT_SELECTED_SOURCE,
    SOURCE_MAPPING_WITHOUT_RESTORABLE_CANDIDATE,
    LIBRARY_MEMBERSHIP_WITHOUT_CANONICAL_TITLE,
    PROVIDER_IDENTITY_WITHOUT_PROVIDER,
    DOWNLOAD_WITHOUT_CONTENT_OPTION,
}

/** Event accepted by domain sinks after all untrusted values have passed the allowlist. */
class SanitizedStructuredDiagnosticEvent internal constructor(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val subsystem: DiagnosticSubsystem,
    val name: DiagnosticEventName,
    val sessionId: String,
    val workflowId: String?,
    val operationId: String?,
    val parentOperationId: String?,
    val workflow: DiagnosticWorkflow?,
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val durationMillis: Long?,
    val attempt: Int?,
    attributes: Map<DiagnosticAttribute, DiagnosticAttributeValue>,
    val schemaVersion: Int,
) {
    val attributes: Map<DiagnosticAttribute, DiagnosticAttributeValue> =
        java.util.Collections.unmodifiableMap(attributes.toMap())
}

/** Converts an event draft to the only shape suitable for persistence or logging. */
object StructuredDiagnosticSanitizer {
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
    private val languageTagPattern = Regex("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,3}$")
    private val pseudonymousReferencePattern = Regex("^[0-9a-f]{16,64}$")
    private val providerIdPattern = Regex("^[a-zA-Z0-9._-]{1,64}$")

    fun sanitize(event: StructuredDiagnosticEvent): SanitizedStructuredDiagnosticEvent? {
        if (event.schemaVersion != StructuredDiagnosticEvent.CURRENT_SCHEMA_VERSION) return null
        if (!event.sessionId.isSafeUuid()) return null
        if (event.workflowId?.isSafeUuid() == false) return null
        if (event.operationId?.isSafeUuid() == false) return null
        if (event.parentOperationId?.isSafeUuid() == false) return null
        if (event.timestampMillis < 0) return null
        if (event.durationMillis?.let { it !in 0..MAX_DURATION_MILLIS } == true) return null
        if (event.attempt?.let { it !in 1..MAX_COUNT.toInt() } == true) return null

        val attributes = event.attributes.mapNotNull { (key, value) ->
            val attribute = DiagnosticAttribute.entries.firstOrNull { it.name.toAttributeKey() == key }
                ?: return@mapNotNull null
            sanitizeAttribute(attribute, value)?.let { attribute to it }
        }.toMap()

        return SanitizedStructuredDiagnosticEvent(
            timestampMillis = event.timestampMillis,
            severity = event.severity,
            subsystem = event.subsystem,
            name = event.name,
            sessionId = event.sessionId.lowercase(),
            workflowId = event.workflowId?.lowercase(),
            operationId = event.operationId?.lowercase(),
            parentOperationId = event.parentOperationId?.lowercase(),
            workflow = event.workflow,
            stage = event.stage,
            outcome = event.outcome,
            durationMillis = event.durationMillis,
            attempt = event.attempt,
            attributes = attributes,
            schemaVersion = event.schemaVersion,
        )
    }

    private fun sanitizeAttribute(
        attribute: DiagnosticAttribute,
        value: DiagnosticAttributeValue,
    ): DiagnosticAttributeValue? = when (attribute) {
        DiagnosticAttribute.SOURCE_ID -> (value as? DiagnosticAttributeValue.Number)
        DiagnosticAttribute.SOURCE_COUNT,
        DiagnosticAttribute.PREFERRED_SOURCE_COUNT,
        DiagnosticAttribute.TARGET_SOURCE_COUNT,
        DiagnosticAttribute.CANDIDATE_COUNT,
        DiagnosticAttribute.CANDIDATE_INDEX,
        DiagnosticAttribute.OBSERVATION_COUNT,
        DiagnosticAttribute.ITEM_COUNT,
        DiagnosticAttribute.ACCEPTED_COUNT,
        DiagnosticAttribute.REJECTED_COUNT,
        DiagnosticAttribute.PAGE_COUNT,
        DiagnosticAttribute.PAGE_INDEX,
        DiagnosticAttribute.BINDING_COUNT,
        DiagnosticAttribute.CHAPTER_COUNT,
        DiagnosticAttribute.VARIANT_COUNT,
        DiagnosticAttribute.QUEUE_DEPTH,
        DiagnosticAttribute.RATING_SOURCE_COUNT,
        DiagnosticAttribute.RATING_VERIFIED_SOURCE_COUNT,
        DiagnosticAttribute.RATING_CORROBORATED_SOURCE_COUNT,
        DiagnosticAttribute.EVENTS_RECEIVED,
        DiagnosticAttribute.EVENTS_SANITIZED,
        DiagnosticAttribute.EVENTS_REJECTED,
        DiagnosticAttribute.LOGCAT_SINK_FAILURES,
        DiagnosticAttribute.HISTORY_SINK_FAILURES,
        DiagnosticAttribute.HISTORY_DROPPED_EVENTS,
        DiagnosticAttribute.EXPORT_FLUSH_TIMEOUTS,
        -> (value as? DiagnosticAttributeValue.Number)?.takeIf { it.value in 0..MAX_COUNT }

        DiagnosticAttribute.BROADENED,
        DiagnosticAttribute.MAPPING_REUSED,
        DiagnosticAttribute.AUTO_CONFIRM_ATTEMPTED,
        DiagnosticAttribute.AMBIGUOUS,
        DiagnosticAttribute.COVER_PRESENT,
        DiagnosticAttribute.SOURCE_ARTWORK_PRESENT,
        DiagnosticAttribute.CANONICAL_ARTWORK_PRESENT,
        DiagnosticAttribute.REQUEST_DATA_PRESENT,
        DiagnosticAttribute.INITIALIZED,
        DiagnosticAttribute.COMPLETED,
        DiagnosticAttribute.IDENTITY_VERIFIED,
        DiagnosticAttribute.RATING_PRESENT,
        DiagnosticAttribute.TSUZUKI_RATING_ENABLED,
        DiagnosticAttribute.TSUZUKI_RATING_PRESENT,
        -> value as? DiagnosticAttributeValue.Flag

        DiagnosticAttribute.LANGUAGE -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { languageTagPattern.matches(it.value) }
        DiagnosticAttribute.PROVIDER_ID,
        DiagnosticAttribute.ADDON_ID,
        -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { providerIdPattern.matches(it.value) }
        DiagnosticAttribute.CANONICAL_TITLE_REF,
        DiagnosticAttribute.MIHON_MANGA_REF,
        -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { pseudonymousReferencePattern.matches(it.value) }

        DiagnosticAttribute.CONFIDENCE_SCORE -> (value as? DiagnosticAttributeValue.Number)
            ?.takeIf { it.value in 0..100 }
        DiagnosticAttribute.CONFIDENCE_BUCKET -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticConfidenceBucket }
        DiagnosticAttribute.ERROR_CATEGORY -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticErrorCategory }
        DiagnosticAttribute.HTTP_STATUS -> (value as? DiagnosticAttributeValue.Number)
            ?.takeIf { it.value in 100L..599L }
        DiagnosticAttribute.CANDIDATE_TYPE -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticCandidateType }
        DiagnosticAttribute.CACHE_STATUS -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticCacheStatus }
        DiagnosticAttribute.INVARIANT_CODE -> (value as? DiagnosticAttributeValue.Code)
            ?.takeIf { it.value is DiagnosticInvariantCode }
    }

    private fun String.toAttributeKey(): String = lowercase()

    private fun String.isSafeUuid(): Boolean = uuidPattern.matches(this)

    private const val MAX_DURATION_MILLIS = 24 * 60 * 60 * 1_000L
    private const val MAX_COUNT = 1_000_000L
}
