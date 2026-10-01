package tachiyomi.domain.tsuzuki.diagnostics

import java.util.UUID
import kotlin.time.Clock

/**
 * Small correlation helper for structured diagnostics.
 *
 * The trace carries opaque UUIDs only; callers still provide allowlisted attributes.
 * It never changes application behavior when recording fails.
 */
data class DiagnosticTrace(
    val recorder: StructuredDiagnosticRecorder,
    val workflow: DiagnosticWorkflow,
    val workflowId: String,
    val operationId: String,
    val parentOperationId: String? = null,
    val canonicalTitleRef: String? = null,
) {
    fun child(): DiagnosticTrace = copy(
        operationId = UUID.randomUUID().toString(),
        parentOperationId = operationId,
    )

    fun event(
        subsystem: DiagnosticSubsystem,
        name: DiagnosticEventName,
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMillis: Long? = null,
        attempt: Int? = null,
        attributes: Map<DiagnosticAttribute, DiagnosticAttributeValue> = emptyMap(),
    ) {
        val encodedAttributes = buildMap {
            canonicalTitleRef?.let {
                put(
                    DiagnosticAttribute.CANONICAL_TITLE_REF.name.lowercase(),
                    DiagnosticAttributeValue.Text(it),
                )
            }
            attributes.forEach { (key, value) -> put(key.name.lowercase(), value) }
        }
        recorder.record(
            StructuredDiagnosticEvent(
                timestampMillis = Clock.System.now().toEpochMilliseconds(),
                severity = severity,
                subsystem = subsystem,
                name = name,
                sessionId = recorder.sessionId,
                operationId = operationId,
                workflowId = workflowId,
                parentOperationId = parentOperationId,
                workflow = workflow,
                stage = stage,
                outcome = outcome,
                durationMillis = durationMillis,
                attempt = attempt,
                attributes = encodedAttributes,
            ),
        )
    }

    companion object {
        fun start(
            recorder: StructuredDiagnosticRecorder,
            workflow: DiagnosticWorkflow,
            canonicalTitleId: String? = null,
            subsystem: DiagnosticSubsystem,
        ): DiagnosticTrace {
            val trace = DiagnosticTrace(
                recorder = recorder,
                workflow = workflow,
                workflowId = UUID.randomUUID().toString(),
                operationId = UUID.randomUUID().toString(),
                canonicalTitleRef = canonicalTitleId?.let(recorder::canonicalTitleReference),
            )
            trace.event(
                subsystem = subsystem,
                name = DiagnosticEventName.WORKFLOW_STARTED,
                stage = DiagnosticStage.WORKFLOW,
                outcome = DiagnosticOutcome.STARTED,
            )
            return trace
        }
    }
}
