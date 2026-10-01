package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder

class DiagnosticCaptureSessionTest {
    @Test
    fun `start and stop emit correlated capture workflow events`() {
        val captureId = "00000000-0000-0000-0000-000000000002"
        val active = ActiveDiagnosticCapture(
            id = captureId,
            startedAtMillis = 10,
            expiresAtMillis = 100,
        )
        val window = DiagnosticCaptureWindow(
            id = captureId,
            startedAtMillis = 10,
            endedAtMillis = 80,
        )
        val state = mockk<DiagnosticCaptureState>()
        every { state.start() } returns active
        every { state.current() } returns active
        every { state.stop() } returns window

        val events = mutableListOf<StructuredDiagnosticEvent>()
        val recorder = mockk<StructuredDiagnosticRecorder>()
        every { recorder.sessionId } returns "00000000-0000-0000-0000-000000000001"
        every { recorder.record(any()) } answers {
            events += firstArg()
        }

        val session = DiagnosticCaptureSession(state, recorder)
        session.start()
        session.stop()

        assertEquals(
            listOf(
                DiagnosticEventName.CAPTURE_SESSION_STARTED,
                DiagnosticEventName.CAPTURE_SESSION_STOPPED,
            ),
            events.map { it.name },
        )
        assertEquals(listOf(captureId, captureId), events.map { it.workflowId })
        assertEquals(
            listOf(DiagnosticWorkflow.DIAGNOSTIC_CAPTURE, DiagnosticWorkflow.DIAGNOSTIC_CAPTURE),
            events.map { it.workflow },
        )
    }
}
