package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicLong

data class DiagnosticRecorderHealthSnapshot(
    val eventsReceived: Long,
    val eventsSanitized: Long,
    val eventsRejected: Long,
    val logcatSinkFailures: Long,
    val historySinkFailures: Long,
    val exportFlushTimeouts: Long,
)

@Inject
@SingleIn(AppScope::class)
class DiagnosticRecorderHealth {
    private val eventsReceived = AtomicLong()
    private val eventsSanitized = AtomicLong()
    private val eventsRejected = AtomicLong()
    private val logcatSinkFailures = AtomicLong()
    private val historySinkFailures = AtomicLong()
    private val exportFlushTimeouts = AtomicLong()

    fun received() {
        eventsReceived.incrementAndGet()
    }

    fun sanitized() {
        eventsSanitized.incrementAndGet()
    }

    fun rejected() {
        eventsRejected.incrementAndGet()
    }

    fun logcatSinkFailed() {
        logcatSinkFailures.incrementAndGet()
    }

    fun historySinkFailed() {
        historySinkFailures.incrementAndGet()
    }

    fun exportFlushTimedOut() {
        exportFlushTimeouts.incrementAndGet()
    }

    fun snapshot() = DiagnosticRecorderHealthSnapshot(
        eventsReceived = eventsReceived.get(),
        eventsSanitized = eventsSanitized.get(),
        eventsRejected = eventsRejected.get(),
        logcatSinkFailures = logcatSinkFailures.get(),
        historySinkFailures = historySinkFailures.get(),
        exportFlushTimeouts = exportFlushTimeouts.get(),
    )
}
