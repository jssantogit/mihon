package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

object StructuredDiagnosticSummary {
    private val json = Json { ignoreUnknownKeys = true }

    fun build(
        structuredHistory: String,
        health: DiagnosticRecorderHealthSnapshot,
    ): String {
        val events = mutableListOf<EventRow>()
        var droppedEvents = 0L

        structuredHistory.lineSequence()
            .filter(String::isNotBlank)
            .forEach { line ->
                val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                when ((record["recordType"] as? JsonPrimitive)?.content) {
                    "event" -> parseEvent(record)?.let(events::add)
                    "dropped_events" -> {
                        droppedEvents += (record["count"] as? JsonPrimitive)?.longOrNull ?: 0L
                    }
                }
            }

        val workflowCount = events.mapNotNull(EventRow::workflowId).distinct().size
        val failures = events.count { it.severity == "ERROR" || it.outcome in failureOutcomes }
        val warnings = events.count { it.severity == "WARN" }
        val invariants = events.count { it.name == "invariant_violation" }
        val slowest = events.filter { it.durationMillis != null }
            .sortedByDescending { it.durationMillis }
            .take(5)

        return buildString {
            appendLine("Tsuzuki Diagnostic Summary")
            appendLine("schema: v2")
            appendLine("events: ${events.size}")
            appendLine("workflows: $workflowCount")
            appendLine("failures: $failures")
            appendLine("warnings: $warnings")
            appendLine("invariant_violations: $invariants")
            appendLine("dropped_history_events: $droppedEvents")
            appendLine()
            appendLine("Recorder health:")
            appendLine("  received=${health.eventsReceived}")
            appendLine("  sanitized=${health.eventsSanitized}")
            appendLine("  rejected=${health.eventsRejected}")
            appendLine("  logcat_sink_failures=${health.logcatSinkFailures}")
            appendLine("  history_sink_failures=${health.historySinkFailures}")
            appendLine("  export_flush_timeouts=${health.exportFlushTimeouts}")
            if (slowest.isNotEmpty()) {
                appendLine()
                appendLine("Slow operations:")
                slowest.forEach { row ->
                    appendLine(
                        "  ${row.subsystem}/${row.name}=${row.durationMillis}ms" +
                            row.workflow?.let { " workflow=$it" }.orEmpty(),
                    )
                }
            }
        }.trimEnd()
    }

    private fun parseEvent(record: JsonObject): EventRow? {
        val schema = (record["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: return null
        if (schema != 2) return null
        return EventRow(
            severity = (record["severity"] as? JsonPrimitive)?.content ?: return null,
            subsystem = (record["subsystem"] as? JsonPrimitive)?.content ?: return null,
            name = (record["name"] as? JsonPrimitive)?.content ?: return null,
            outcome = (record["outcome"] as? JsonPrimitive)?.content ?: return null,
            workflow = (record["workflow"] as? JsonPrimitive)?.content,
            workflowId = (record["workflowId"] as? JsonPrimitive)?.content,
            durationMillis = (record["durationMillis"] as? JsonPrimitive)?.longOrNull,
        )
    }

    private data class EventRow(
        val severity: String,
        val subsystem: String,
        val name: String,
        val outcome: String,
        val workflow: String?,
        val workflowId: String?,
        val durationMillis: Long?,
    )

    private val failureOutcomes = setOf(
        "failed",
        "threw",
        "typed_failure",
        "timeout",
        "not_found_no_candidates",
        "not_found_with_source_failures",
    )
}
