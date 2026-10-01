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
    private val health: DiagnosticRecorderHealth? = null,
) {
    fun record(event: StructuredDiagnosticEvent) {
        health?.received()
        val sanitized = bestEffort { StructuredDiagnosticSanitizer.sanitize(event) }.getOrNull()
        if (sanitized == null) {
            health?.rejected()
            return
        }
        health?.sanitized()
        val encoded = bestEffort { StructuredDiagnosticHistoryJson.serialize(sanitized) }.getOrNull()
        if (encoded == null) {
            health?.rejected()
            return
        }

        if (bestEffort { logcatSink(sanitized.severity.toLogPriority(), encoded) }.isFailure) {
            health?.logcatSinkFailed()
        }
        if (bestEffort(persistenceEnabled).getOrDefault(false)) {
            if (bestEffort { historySink(sanitized) }.isFailure) {
                health?.historySinkFailed()
            }
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
