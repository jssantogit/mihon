package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import java.util.UUID
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
class DiagnosticCaptureSession(
    private val state: DiagnosticCaptureState,
    private val recorder: StructuredDiagnosticRecorder,
) {
    fun current(): ActiveDiagnosticCapture? = state.current()

    fun start(): ActiveDiagnosticCapture {
        val capture = state.start()
        record(
            captureId = capture.id,
            name = DiagnosticEventName.CAPTURE_SESSION_STARTED,
            outcome = DiagnosticOutcome.STARTED,
        )
        return capture
    }

    fun stop(): DiagnosticCaptureWindow? {
        val active = state.current() ?: return state.stop()
        record(
            captureId = active.id,
            name = DiagnosticEventName.CAPTURE_SESSION_STOPPED,
            outcome = DiagnosticOutcome.SUCCEEDED,
        )
        return state.stop()
    }

    private fun record(
        captureId: String,
        name: DiagnosticEventName,
        outcome: DiagnosticOutcome,
    ) {
        recorder.record(
            StructuredDiagnosticEvent(
                timestampMillis = Clock.System.now().toEpochMilliseconds(),
                severity = tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.DIAGNOSTICS,
                name = name,
                sessionId = recorder.sessionId,
                operationId = UUID.randomUUID().toString(),
                workflowId = captureId,
                workflow = DiagnosticWorkflow.DIAGNOSTIC_CAPTURE,
                stage = DiagnosticStage.CAPTURE,
                outcome = outcome,
            ),
        )
    }
}
