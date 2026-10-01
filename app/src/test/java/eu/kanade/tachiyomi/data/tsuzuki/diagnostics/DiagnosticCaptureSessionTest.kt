package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
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
            events += firstArg<StructuredDiagnosticEvent>()
        }

        val session = DiagnosticCaptureSession(state, recorder)
        session.start()
        session.stop()

        verifyOrder {
            state.current()
            recorder.record(any())
            state.stop()
        }
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

    @Test
    fun `stopping without an active capture does not emit a duplicate stop event`() {
        val state = mockk<DiagnosticCaptureState>()
        every { state.current() } returns null
        every { state.stop() } returns DiagnosticCaptureWindow(
            id = "00000000-0000-0000-0000-000000000002",
            startedAtMillis = 10,
            endedAtMillis = 80,
        )
        val recorder = mockk<StructuredDiagnosticRecorder>(relaxed = true)

        DiagnosticCaptureSession(state, recorder).stop()

        io.mockk.verify(exactly = 0) { recorder.record(any()) }
    }
}
