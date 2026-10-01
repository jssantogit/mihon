package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.data.tsuzuki.diagnostics.StructuredDiagnosticHistoryJson
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.SanitizedStructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer

/** Sanitizes once, then sends the same codec output independently to each enabled sink. */
internal class StructuredDiagnosticRecordingPipeline(
    private val persistenceEnabled: () -> Boolean,
    private val logcatSink: (LogPriority, String) -> Unit,
    private val historySink: (SanitizedStructuredDiagnosticEvent) -> Unit,
) {
    fun record(event: StructuredDiagnosticEvent) {
        val sanitized = bestEffort { StructuredDiagnosticSanitizer.sanitize(event) }.getOrNull() ?: return
        val encoded = bestEffort { StructuredDiagnosticHistoryJson.serialize(sanitized) }.getOrNull() ?: return

        bestEffort { logcatSink(sanitized.severity.toLogPriority(), encoded) }
        if (bestEffort(persistenceEnabled).getOrDefault(false)) {
            bestEffort { historySink(sanitized) }
        }
    }

    private inline fun <T> bestEffort(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun DiagnosticSeverity.toLogPriority(): LogPriority = when (this) {
        DiagnosticSeverity.DEBUG -> LogPriority.DEBUG
        DiagnosticSeverity.INFO -> LogPriority.INFO
        DiagnosticSeverity.WARN -> LogPriority.WARN
        DiagnosticSeverity.ERROR -> LogPriority.ERROR
    }
}
