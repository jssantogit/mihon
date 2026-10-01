package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer

class DiagnosticCrashContextStoreTest {
    @Test
    fun `crash context exposes only safe structured last-event fields`() {
        val store = DiagnosticCrashContextStore()
        val event = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1,
                severity = DiagnosticSeverity.ERROR,
                subsystem = DiagnosticSubsystem.ARTWORK,
                name = DiagnosticEventName.INVARIANT_VIOLATION,
                sessionId = "00000000-0000-0000-0000-000000000001",
                workflowId = "00000000-0000-0000-0000-000000000002",
                operationId = "00000000-0000-0000-0000-000000000003",
                workflow = DiagnosticWorkflow.ARTWORK_RESOLUTION,
                stage = DiagnosticStage.RENDER,
                outcome = DiagnosticOutcome.FAILED,
            ),
        )!!

        store.observe(event)

        val description = store.describe()
        assertTrue(description.contains("last_subsystem=ARTWORK"))
        assertTrue(description.contains("last_event=INVARIANT_VIOLATION"))
        assertTrue(description.contains("last_workflow=ARTWORK_RESOLUTION"))
        assertTrue(description.contains("workflow_ref=00000000"))
        assertTrue(description.contains("operation_ref=00000000"))
        assertFalse(description.contains("http"))
        assertFalse(description.contains("title"))
    }

    @Test
    fun `clear removes the process crash context`() {
        val store = DiagnosticCrashContextStore()
        val event = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1,
                severity = DiagnosticSeverity.ERROR,
                subsystem = DiagnosticSubsystem.ARTWORK,
                name = DiagnosticEventName.INVARIANT_VIOLATION,
                sessionId = "00000000-0000-0000-0000-000000000001",
                stage = DiagnosticStage.RENDER,
                outcome = DiagnosticOutcome.FAILED,
            ),
        )!!
        store.observe(event)

        store.clear()

        assertTrue(store.describe().contains("no structured event observed in this process"))
    }

    @Test
    fun `empty crash context has fixed marker`() {
        assertTrue(
            DiagnosticCrashContextStore().describe()
                .contains("no structured event observed in this process"),
        )
    }
}
