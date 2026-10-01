package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredDiagnosticSummaryTest {
    @Test
    fun `summary reports workflows failures invariants dropped events and recorder health`() {
        val history = listOf(
            """{"schemaVersion":2,"recordType":"event","timestampMillis":1,"severity":"INFO","subsystem":"ARTWORK","name":"artwork_resolve_started","sessionId":"00000000-0000-0000-0000-000000000001","workflowId":"00000000-0000-0000-0000-000000000002","operationId":"00000000-0000-0000-0000-000000000003","workflow":"artwork_resolution","stage":"resolve","outcome":"started","attributes":{}}""",
            """{"schemaVersion":2,"recordType":"event","timestampMillis":2,"severity":"ERROR","subsystem":"IMAGE","name":"invariant_violation","sessionId":"00000000-0000-0000-0000-000000000001","workflowId":"00000000-0000-0000-0000-000000000002","operationId":"00000000-0000-0000-0000-000000000004","workflow":"artwork_resolution","stage":"render","outcome":"failed","durationMillis":1200,"attributes":{}}""",
            """{"schemaVersion":2,"recordType":"dropped_events","timestampMillis":3,"count":4}""",
        ).joinToString("\n")

        val summary = StructuredDiagnosticSummary.build(
            structuredHistory = history,
            health = DiagnosticRecorderHealthSnapshot(
                eventsReceived = 2,
                eventsSanitized = 2,
                eventsRejected = 0,
                logcatSinkFailures = 0,
                historySinkFailures = 0,
                exportFlushTimeouts = 1,
            ),
        )

        assertTrue(summary.contains("workflows: 1"))
        assertTrue(summary.contains("failures: 1"))
        assertTrue(summary.contains("invariant_violations: 1"))
        assertTrue(summary.contains("dropped_history_events: 4"))
        assertTrue(summary.contains("export_flush_timeouts=1"))
        assertTrue(summary.contains("IMAGE/invariant_violation=1200ms"))
    }
}
