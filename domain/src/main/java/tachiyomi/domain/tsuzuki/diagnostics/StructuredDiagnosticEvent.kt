package tachiyomi.domain.tsuzuki.diagnostics

/** Versioned, provider-neutral diagnostics contract. Free-form messages and throwables are absent. */
data class StructuredDiagnosticEvent(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val subsystem: DiagnosticSubsystem,
    val name: DiagnosticEventName,
    val sessionId: String,
    val operationId: String?,
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val durationMillis: Long? = null,
    val attempt: Int? = null,
    val attributes: Map<String, DiagnosticAttributeValue> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class DiagnosticSubsystem {
    NETWORK,
    SOURCE,
    LIBRARY,
    READER,
    DOWNLOAD,
}

enum class DiagnosticEventName {
    SOURCE_RESOLVE_STARTED,
    SOURCE_RESOLVE_MAPPING_REUSED,
    SOURCE_RESOLVE_PREFERRED_SOURCES,
    SOURCE_SEARCH_STARTED,
    SOURCE_SEARCH_COMPLETED,
    SOURCE_SEARCH_FAILED,
    SOURCE_MATCH_EVALUATED,
    SOURCE_MAPPING_CONFIRMATION_FAILED,
    SOURCE_RESOLVE_COMPLETED,
}

enum class DiagnosticStage {
    RESOLVE,
    PREFERRED_SOURCES,
    SEARCH,
    MATCH,
    CONFIRMATION,
    COMPLETE,
}

enum class DiagnosticOutcome {
    STARTED,
    SUCCEEDED,
    REUSED,
    CANDIDATES,
    NEEDS_CONFIRMATION,
    NOT_FOUND_NO_CANDIDATES,
    NOT_FOUND_WITH_SOURCE_FAILURES,
    NO_PREFERRED_SOURCES,
    FAILED,
    CANCELLED,
}

enum class DiagnosticAttribute {
    SOURCE_ID,
    SOURCE_COUNT,
    PREFERRED_SOURCE_COUNT,
    TARGET_SOURCE_COUNT,
    CANDIDATE_COUNT,
    BROADENED,
    MAPPING_REUSED,
    LANGUAGE,
    CANONICAL_TITLE_REF,
    MIHON_MANGA_REF,
    CONFIDENCE_SCORE,
    CONFIDENCE_BUCKET,
    ERROR_CATEGORY,
    HTTP_STATUS,
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
    UNKNOWN,
}

enum class DiagnosticConfidenceBucket : DiagnosticSafeCode {
    LOW,
    MEDIUM,
    HIGH,
}

/** Event accepted by domain sinks after all untrusted values have passed the allowlist. */
class SanitizedStructuredDiagnosticEvent internal constructor(
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val subsystem: DiagnosticSubsystem,
    val name: DiagnosticEventName,
    val sessionId: String,
    val operationId: String?,
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

    fun sanitize(event: StructuredDiagnosticEvent): SanitizedStructuredDiagnosticEvent? {
        if (event.schemaVersion != StructuredDiagnosticEvent.CURRENT_SCHEMA_VERSION) return null
        if (!event.sessionId.isSafeUuid() || event.operationId?.isSafeUuid() == false) return null
        if (event.timestampMillis < 0) return null
        if (event.durationMillis?.let { it !in 0..MAX_DURATION_MILLIS } == true) return null
        if (event.attempt?.let { it !in 1..MAX_ATTEMPTS } == true) return null

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
            operationId = event.operationId?.lowercase(),
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
        -> (value as? DiagnosticAttributeValue.Number)?.takeIf { it.value in 0..MAX_COUNT }
        DiagnosticAttribute.BROADENED,
        DiagnosticAttribute.MAPPING_REUSED,
        -> value as? DiagnosticAttributeValue.Flag
        DiagnosticAttribute.LANGUAGE -> (value as? DiagnosticAttributeValue.Text)
            ?.takeIf { languageTagPattern.matches(it.value) }
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
    }

    private fun String.toAttributeKey(): String = lowercase()

    private fun String.isSafeUuid(): Boolean = uuidPattern.matches(this)

    private const val MAX_DURATION_MILLIS = 24 * 60 * 60 * 1_000L
    private const val MAX_ATTEMPTS = 100
    private const val MAX_COUNT = 1_000_000L
}
