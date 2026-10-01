package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.diagnostics.SanitizedStructuredDiagnosticEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock

data class DiagnosticCrashContextSnapshot(
    val timestampMillis: Long,
    val subsystem: String,
    val name: String,
    val stage: String,
    val outcome: String,
    val workflow: String?,
    val workflowId: String?,
    val operationId: String?,
)

@Inject
@SingleIn(AppScope::class)
class DiagnosticCrashContextStore {
    private val lastEvent = AtomicReference<DiagnosticCrashContextSnapshot?>()

    fun observe(event: SanitizedStructuredDiagnosticEvent) {
        lastEvent.set(
            DiagnosticCrashContextSnapshot(
                timestampMillis = event.timestampMillis,
                subsystem = event.subsystem.name,
                name = event.name.name,
                stage = event.stage.name,
                outcome = event.outcome.name,
                workflow = event.workflow?.name,
                workflowId = event.workflowId,
                operationId = event.operationId,
            ),
        )
    }

    fun clear() {
        lastEvent.set(null)
    }

    fun describe(): String {
        val snapshot = lastEvent.get() ?: return "(no structured event observed in this process)"
        val age = (Clock.System.now().toEpochMilliseconds() - snapshot.timestampMillis).coerceAtLeast(0)
        return buildString {
            appendLine("last_subsystem=${snapshot.subsystem}")
            appendLine("last_event=${snapshot.name}")
            appendLine("last_stage=${snapshot.stage}")
            appendLine("last_outcome=${snapshot.outcome}")
            snapshot.workflow?.let { appendLine("last_workflow=$it") }
            snapshot.workflowId?.let { appendLine("workflow_ref=${it.take(8)}") }
            snapshot.operationId?.let { appendLine("operation_ref=${it.take(8)}") }
            append("event_age_ms=$age")
        }
    }
}
