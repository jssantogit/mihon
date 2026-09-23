package tachiyomi.domain.tsuzuki.chapter.diagnostics

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

/** Processing boundary recorded by the temporary chapter-inventory diagnostic. */
enum class ChapterInventoryDiagnosticStage {
    INVENTORY,
    PROBE,
    RECONCILIATION,
    PERSISTENCE,
    UI,
}

/** Result category. Failures remain observational and do not change runtime results. */
enum class ChapterInventoryDiagnosticOutcome {
    SUCCESS,
    EMPTY,
    NETWORK_ERROR,
    EXTENSION_ERROR,
    TIMEOUT,
    NO_BINDING,
    LOW_CONFIDENCE,
    PARTIAL,
}

/** Closed reason set keeps reports free of arbitrary exception or provider text. */
enum class ChapterInventoryDiagnosticReason {
    DUPLICATE,
    MISSING_SOURCE_ID,
    MISSING_SOURCE_URL,
    INVALID_LABEL,
    PARSER_ERROR,
    LOW_CONFIDENCE,
    IDENTITY_MISMATCH,
    NO_BINDING,
    INVENTORY_EMPTY,
    BINDING_UNAVAILABLE,
    BINDING_CONFIRMATION_REQUIRED,
    BINDING_SEARCH_FAILED,
    BINDING_MATERIALIZATION_FAILED,
    SOURCE_DISABLED,
    SOURCE_NOT_INSTALLED,
    CHALLENGE_REQUIRED,
    CACHE_SNAPSHOT,
    REFRESHED_SNAPSHOT,
    PERSISTED_MAPPED,
    PERSISTED_UNMAPPED,
    FILTERED_FROM_UI,
}

/**
 * Sanitized counters about one observation boundary.
 *
 * String values must be technical IDs, locale tags, or already-normalized chapter labels.
 * Implementations must sanitize them again before retaining or exporting the event.
 */
data class ChapterInventoryDiagnosticEvent(
    val stage: ChapterInventoryDiagnosticStage,
    val outcome: ChapterInventoryDiagnosticOutcome,
    val sourceId: Long? = null,
    val addonId: String? = null,
    val language: String? = null,
    val elapsedMillis: Long? = null,
    val received: Int? = null,
    val accepted: Int? = null,
    val provisional: Int? = null,
    val discarded: Int? = null,
    val inferred: Int? = null,
    val unavailable: Int? = null,
    val labels: List<String> = emptyList(),
    val gaps: List<Int> = emptyList(),
    val reasons: Map<ChapterInventoryDiagnosticReason, Int> = emptyMap(),
)

/**
 * Opt-in, in-memory capture for a single canonical title.
 *
 * The domain contract intentionally has no persistence or logging API. Callers must not include
 * raw labels, URLs, exception messages, or other user/provider content in events.
 */
interface ChapterInventoryDiagnostics {
    /** Starts a fresh diagnostic session and returns its opaque session ID. */
    fun start(canonicalTitleId: String): String

    /** Stops collecting but retains the sanitized report until it is copied or cleared. */
    fun stop()

    /** Deletes the report and stops collection. */
    fun clear()

    /** Whether collection is active for this canonical title. */
    fun isRecording(canonicalTitleId: String): Boolean

    /** Records one structured, sanitized event if collection is active. */
    fun record(event: ChapterInventoryDiagnosticEvent)

    /** Returns an exportable report, or an empty string when no report is present. */
    fun report(): String
}

/** Keeps observational diagnostics from changing the caller's operational outcome. */
fun ChapterInventoryDiagnostics.recordIfEnabled(
    canonicalTitleId: String,
    event: ChapterInventoryDiagnosticEvent,
) {
    try {
        if (isRecording(canonicalTitleId)) record(event)
    } catch (_: Exception) {
        // Diagnostic collection is best-effort and must never affect chapter processing.
    }
}

/** Default for consumers that are constructed outside the app instrumentation graph. */
object NoOpChapterInventoryDiagnostics : ChapterInventoryDiagnostics {
    override fun start(canonicalTitleId: String): String = ""
    override fun stop() = Unit
    override fun clear() = Unit
    override fun isRecording(canonicalTitleId: String): Boolean = false
    override fun record(event: ChapterInventoryDiagnosticEvent) = Unit
    override fun report(): String = ""
}

/** Safe numeric/semantic labels derived from structured canonical chapter identity. */
object ChapterInventoryDiagnosticLabels {
    private val allowedLabel = Regex(
        "^(?:[0-9]{1,9}(?:\\.[0-9]{1,9})?[a-z]{0,2}|(?:prologue|epilogue|extra|special|oneshot)(?::[0-9]{1,9})?)$",
    )

    fun fromIdentity(identity: CanonicalChapterIdentity): String? {
        val base = identity.baseNumber?.takeIf { it in 0..999_999_999 }
        val raw = when (identity.type) {
            CanonicalChapterType.REGULAR -> {
                if (base == null) return null
                buildString {
                    append(base)
                    identity.part?.takeIf { it in 0..999_999_999 }?.let { append('.').append(it) }
                    identity.alphaSuffix?.takeIf { it.matches(Regex("^[a-z]{1,2}$", RegexOption.IGNORE_CASE)) }
                        ?.let { append(it.lowercase()) }
                }
            }

            CanonicalChapterType.PROLOGUE -> semantic("prologue", base)
            CanonicalChapterType.EPILOGUE -> semantic("epilogue", base)
            CanonicalChapterType.EXTRA -> semantic("extra", base)
            CanonicalChapterType.SPECIAL -> semantic("special", base)
            CanonicalChapterType.ONESHOT -> semantic("oneshot", base)
            CanonicalChapterType.UNKNOWN -> return null
        }
        return raw.takeIf(allowedLabel::matches)
    }

    /** Preserves source order at the ends and reports only gaps between observed integers. */
    fun boundariesAndGaps(labels: List<String>): Pair<List<String>, List<Int>> {
        val safe = labels.mapNotNull(::sanitize).distinct()
        if (safe.isEmpty()) return emptyList<String>() to emptyList()

        val observedIntegers = safe.mapNotNull { it.toIntOrNull() }.distinct().sorted()
        val missing = buildList {
            observedIntegers.zipWithNext().forEach { (before, after) ->
                if (after - before > 1) {
                    var next = before + 1
                    while (next < after && size < MAX_GAP_SAMPLES) {
                        add(next)
                        next++
                    }
                }
            }
        }
        val gapNeighbors = missing.flatMap { gap ->
            listOf((gap - 1).toString(), (gap + 1).toString())
        }.filter(safe::contains)
        val boundaryLabels = (listOf(safe.first(), safe.last()) + gapNeighbors).distinct().take(MAX_LABELS)
        return boundaryLabels to missing
    }

    /** Accept only normalized chapter tokens; arbitrary provider strings are discarded. */
    fun sanitize(label: String): String? = label.lowercase().takeIf(allowedLabel::matches)

    private fun semantic(type: String, number: Int?): String =
        number?.let { "$type:$it" } ?: type

    private const val MAX_GAP_SAMPLES = 64
    private const val MAX_LABELS = 16
}
