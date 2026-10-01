package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import kotlin.time.Clock

data class ActiveDiagnosticCapture(
    val id: String,
    val startedAtMillis: Long,
    val expiresAtMillis: Long,
)

data class DiagnosticCaptureWindow(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
)

@Inject
@SingleIn(AppScope::class)
class DiagnosticCaptureState(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun start(durationMillis: Long = DEFAULT_CAPTURE_DURATION_MILLIS): ActiveDiagnosticCapture {
        require(durationMillis in MIN_CAPTURE_DURATION_MILLIS..MAX_CAPTURE_DURATION_MILLIS)
        val now = Clock.System.now().toEpochMilliseconds()
        val capture = ActiveDiagnosticCapture(
            id = UUID.randomUUID().toString(),
            startedAtMillis = now,
            expiresAtMillis = now + durationMillis,
        )
        preferences.edit()
            .putString(KEY_ACTIVE_ID, capture.id)
            .putLong(KEY_ACTIVE_START, capture.startedAtMillis)
            .putLong(KEY_ACTIVE_EXPIRY, capture.expiresAtMillis)
            .apply()
        return capture
    }

    @Synchronized
    fun current(): ActiveDiagnosticCapture? {
        val id = preferences.getString(KEY_ACTIVE_ID, null) ?: return null
        val startedAt = preferences.getLong(KEY_ACTIVE_START, -1L)
        val expiresAt = preferences.getLong(KEY_ACTIVE_EXPIRY, -1L)
        if (startedAt < 0L || expiresAt <= startedAt) {
            clearActive()
            return null
        }

        val now = Clock.System.now().toEpochMilliseconds()
        if (now >= expiresAt) {
            persistCompleted(
                DiagnosticCaptureWindow(
                    id = id,
                    startedAtMillis = startedAt,
                    endedAtMillis = expiresAt,
                ),
            )
            clearActive()
            return null
        }
        return ActiveDiagnosticCapture(id, startedAt, expiresAt)
    }

    @Synchronized
    fun stop(): DiagnosticCaptureWindow? {
        val active = current() ?: return latestCompleted()
        val now = Clock.System.now().toEpochMilliseconds()
        val window = DiagnosticCaptureWindow(
            id = active.id,
            startedAtMillis = active.startedAtMillis,
            endedAtMillis = minOf(now, active.expiresAtMillis),
        )
        persistCompleted(window)
        clearActive()
        return window
    }

    @Synchronized
    fun latestWindow(): DiagnosticCaptureWindow? {
        val active = current()
        if (active != null) {
            return DiagnosticCaptureWindow(
                id = active.id,
                startedAtMillis = active.startedAtMillis,
                endedAtMillis = Clock.System.now().toEpochMilliseconds().coerceAtMost(active.expiresAtMillis),
            )
        }
        return latestCompleted()
    }

    fun isDetailedCaptureActive(): Boolean = current() != null

    fun filterForExport(structuredHistory: String): String {
        val window = latestWindow() ?: return structuredHistory
        return structuredHistory.lineSequence()
            .filter(String::isNotBlank)
            .filter { line ->
                val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@filter false
                val timestamp = (record["timestampMillis"] as? JsonPrimitive)?.longOrNull
                    ?: return@filter false
                timestamp in window.startedAtMillis..window.endedAtMillis
            }
            .joinToString("\n")
    }

    fun describeWindow(): String? {
        val active = current()
        val window = latestWindow() ?: return null
        return buildString {
            append("capture_id=")
            append(window.id.take(8))
            append(" start_ms=")
            append(window.startedAtMillis)
            append(" end_ms=")
            append(window.endedAtMillis)
            append(" active=")
            append(active != null && active.id == window.id)
        }
    }

    @Synchronized
    private fun latestCompleted(): DiagnosticCaptureWindow? {
        val id = preferences.getString(KEY_LAST_ID, null) ?: return null
        val start = preferences.getLong(KEY_LAST_START, -1L)
        val end = preferences.getLong(KEY_LAST_END, -1L)
        return if (start >= 0L && end >= start) {
            DiagnosticCaptureWindow(id, start, end)
        } else {
            null
        }
    }

    private fun persistCompleted(window: DiagnosticCaptureWindow) {
        preferences.edit()
            .putString(KEY_LAST_ID, window.id)
            .putLong(KEY_LAST_START, window.startedAtMillis)
            .putLong(KEY_LAST_END, window.endedAtMillis)
            .apply()
    }

    private fun clearActive() {
        preferences.edit()
            .remove(KEY_ACTIVE_ID)
            .remove(KEY_ACTIVE_START)
            .remove(KEY_ACTIVE_EXPIRY)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tsuzuki_diagnostic_capture"
        const val KEY_ACTIVE_ID = "active_id"
        const val KEY_ACTIVE_START = "active_start"
        const val KEY_ACTIVE_EXPIRY = "active_expiry"
        const val KEY_LAST_ID = "last_id"
        const val KEY_LAST_START = "last_start"
        const val KEY_LAST_END = "last_end"

        const val DEFAULT_CAPTURE_DURATION_MILLIS = 15L * 60 * 1_000
        const val MIN_CAPTURE_DURATION_MILLIS = 60_000L
        const val MAX_CAPTURE_DURATION_MILLIS = 30L * 60 * 1_000
    }
}
