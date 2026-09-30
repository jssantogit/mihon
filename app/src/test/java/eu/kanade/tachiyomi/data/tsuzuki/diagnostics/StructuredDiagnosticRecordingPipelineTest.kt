package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import logcat.LogPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import java.util.concurrent.CancellationException

class StructuredDiagnosticRecordingPipelineTest {
    @Test
    fun `sends the same sanitized codec output to logcat and persistence`() {
        val logs = mutableListOf<Pair<LogPriority, String>>()
        val history = mutableListOf<String>()
        val pipeline = StructuredDiagnosticRecordingPipeline(
            persistenceEnabled = { true },
            logcatSink = { priority, line -> logs += priority to line },
            historySink = {
                history += tachiyomi.data.tsuzuki.diagnostics.StructuredDiagnosticHistoryJson.serialize(it)
            },
        )

        pipeline.record(eventWithUnsafeFields())

        assertEquals(1, logs.size)
        assertEquals(LogPriority.WARN, logs.single().first)
        assertEquals(logs.single().second, history.single())
        assertFalse(logs.single().second.contains("manga title"))
        assertFalse(logs.single().second.contains("token-secret"))
        assertFalse(logs.single().second.contains("example.invalid"))
        assertTrue(logs.single().second.contains("source_resolve_completed"))
    }

    @Test
    fun `incognito suppresses persistence while retaining sanitized logcat diagnostics`() {
        val logs = mutableListOf<String>()
        var persisted = false
        val pipeline = StructuredDiagnosticRecordingPipeline(
            persistenceEnabled = { false },
            logcatSink = { _, line -> logs += line },
            historySink = { persisted = true },
        )

        pipeline.record(eventWithUnsafeFields())

        assertEquals(1, logs.size)
        assertFalse(persisted)
        assertFalse(logs.single().contains("manga title"))
    }

    @Test
    fun `logcat failure does not prevent persistent history`() {
        var persisted = false
        val pipeline = StructuredDiagnosticRecordingPipeline(
            persistenceEnabled = { true },
            logcatSink = { _, _ -> error("logger unavailable") },
            historySink = { persisted = true },
        )

        pipeline.record(eventWithUnsafeFields())

        assertTrue(persisted)
    }

    @Test
    fun `cancellation from a sink propagates`() {
        val pipeline = StructuredDiagnosticRecordingPipeline(
            persistenceEnabled = { true },
            logcatSink = { _, _ -> throw CancellationException("cancelled") },
            historySink = {},
        )

        try {
            pipeline.record(eventWithUnsafeFields())
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation remains observable to the operation owner.
        }
    }

    private fun eventWithUnsafeFields() = StructuredDiagnosticEvent(
        timestampMillis = 1234,
        severity = DiagnosticSeverity.WARN,
        subsystem = DiagnosticSubsystem.SOURCE,
        name = DiagnosticEventName.SOURCE_RESOLVE_COMPLETED,
        sessionId = "00000000-0000-0000-0000-000000000001",
        operationId = "00000000-0000-0000-0000-000000000002",
        stage = DiagnosticStage.COMPLETE,
        outcome = DiagnosticOutcome.NOT_FOUND_WITH_SOURCE_FAILURES,
        attributes = mapOf(
            "canonical_title_ref" to DiagnosticAttributeValue.Text("manga title"),
            "search_text" to DiagnosticAttributeValue.Text("manga title"),
            "language" to DiagnosticAttributeValue.Text("https://example.invalid/?token=token-secret"),
        ),
    )
}
