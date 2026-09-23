package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticLabels
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Temporary opt-in diagnostics retained only in process memory.
 *
 * Both the number of retained events and the UTF-8 report size are bounded. Arbitrary provider
 * strings are rejected at the export boundary; only numeric/semantic labels and small technical
 * source identifiers are retained.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class InMemoryChapterInventoryDiagnostics internal constructor(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxReportBytes: Int = DEFAULT_MAX_REPORT_BYTES,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : ChapterInventoryDiagnostics {

    @Inject
    constructor() : this(DEFAULT_MAX_EVENTS, DEFAULT_MAX_REPORT_BYTES, { UUID.randomUUID().toString() })

    private val lock = Any()
    private val eventLines = mutableListOf<String>()
    private var recordingTitleHash: String? = null
    private var reportTitleHash: String? = null
    private var reportSessionId: String? = null

    init {
        require(maxEvents > 0) { "At least one diagnostic event must be retainable" }
        require(maxReportBytes >= MIN_REPORT_BYTES) { "Diagnostic report limit is too small" }
    }

    override fun start(canonicalTitleId: String): String {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id is required" }
        val sessionId = sessionIdFactory().takeIf(SESSION_ID::matches) ?: UUID.randomUUID().toString()
        val titleHash = hashTitle(canonicalTitleId)
        synchronized(lock) {
            eventLines.clear()
            recordingTitleHash = titleHash
            reportTitleHash = titleHash
            reportSessionId = sessionId
        }
        return sessionId
    }

    override fun stop() {
        synchronized(lock) {
            recordingTitleHash = null
        }
    }

    override fun clear() {
        synchronized(lock) {
            eventLines.clear()
            recordingTitleHash = null
            reportTitleHash = null
            reportSessionId = null
        }
    }

    override fun isRecording(canonicalTitleId: String): Boolean {
        if (canonicalTitleId.isBlank()) return false
        val titleHash = hashTitle(canonicalTitleId)
        return synchronized(lock) { recordingTitleHash == titleHash }
    }

    override fun record(event: ChapterInventoryDiagnosticEvent) {
        synchronized(lock) {
            if (recordingTitleHash == null) return
            eventLines += event.toSafeLine()
            trimToBounds()
        }
    }

    override fun report(): String = synchronized(lock) {
        if (reportSessionId == null || reportTitleHash == null) return@synchronized ""
        renderReport()
    }

    private fun ChapterInventoryDiagnosticEvent.toSafeLine(): String = buildString {
        append(stage.name)
        append("|outcome=").append(outcome.name)
        sourceId?.takeIf { it >= 0L }?.let { append("|sourceId=").append(it) }
        addonId?.takeIf(SAFE_ADDON_ID::matches)?.let { append("|addonId=").append(it) }
        language?.takeIf(SAFE_LANGUAGE::matches)?.let { append("|language=").append(it) }
        elapsedMillis?.takeIf { it >= 0L }?.let { append("|elapsedMs=").append(it) }
        received?.takeIf { it >= 0 }?.let { append("|received=").append(it) }
        accepted?.takeIf { it >= 0 }?.let { append("|accepted=").append(it) }
        provisional?.takeIf { it >= 0 }?.let { append("|provisional=").append(it) }
        discarded?.takeIf { it >= 0 }?.let { append("|discarded=").append(it) }
        inferred?.takeIf { it >= 0 }?.let { append("|inferred=").append(it) }
        unavailable?.takeIf { it >= 0 }?.let { append("|unavailable=").append(it) }
        val safeLabels = labels.asSequence()
            .take(MAX_LABELS * 4)
            .mapNotNull(ChapterInventoryDiagnosticLabels::sanitize)
            .distinct()
            .take(MAX_LABELS)
            .toList()
        if (safeLabels.isNotEmpty()) append("|labels=").append(safeLabels.joinToString(","))
        val safeGaps = gaps.asSequence()
            .take(MAX_GAPS * 4)
            .filter { it in 0..MAX_CHAPTER_NUMBER }
            .distinct()
            .take(MAX_GAPS)
            .toList()
        if (safeGaps.isNotEmpty()) append("|gaps=").append(safeGaps.joinToString(","))
        val safeReasons = reasons.entries
            .asSequence()
            .filter { it.value > 0 }
            .sortedBy { it.key.name }
            .take(MAX_REASONS)
            .toList()
        if (safeReasons.isNotEmpty()) {
            append("|reasons=")
            append(safeReasons.joinToString(",") { "${it.key.name}:${it.value.coerceAtMost(MAX_COUNTER)}" })
        }
    }

    private fun trimToBounds() {
        while (eventLines.size > maxEvents) eventLines.removeAt(0)
        while (eventLines.isNotEmpty() && reportByteSize() > maxReportBytes) eventLines.removeAt(0)
    }

    private fun reportByteSize(): Int = renderReport().toByteArray(StandardCharsets.UTF_8).size

    private fun renderReport(): String = buildString {
        appendLine("Tsuzuki chapter inventory diagnostic v1")
        append("sessionId=").appendLine(reportSessionId)
        append("titleHash=").appendLine(reportTitleHash)
        eventLines.forEach { appendLine(it) }
    }.trimEnd()

    private fun hashTitle(canonicalTitleId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalTitleId.toByteArray(StandardCharsets.UTF_8))
        return bytes.take(TITLE_HASH_BYTES).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val DEFAULT_MAX_EVENTS = 200
        const val DEFAULT_MAX_REPORT_BYTES = 32_768
        const val MIN_REPORT_BYTES = 256
        const val TITLE_HASH_BYTES = 8
        const val MAX_LABELS = 16
        const val MAX_GAPS = 64
        const val MAX_REASONS = 20
        const val MAX_CHAPTER_NUMBER = 999_999_999
        const val MAX_COUNTER = 999_999_999
        val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        val SAFE_ADDON_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val SAFE_LANGUAGE = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,2}$")
    }
}
