package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.CancellationException
import tachiyomi.data.tsuzuki.diagnostics.StructuredDiagnosticHistory
import tachiyomi.domain.tsuzuki.diagnostics.SanitizedStructuredDiagnosticEvent
import java.io.File

/** Lazy, private local history access. Storage failures never escape into app operations. */
@Inject
@SingleIn(AppScope::class)
class LocalStructuredDiagnosticHistory(
    context: Context,
    private val basePreferences: BasePreferences,
) {
    private val history by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StructuredDiagnosticHistory(
            directory = File(context.noBackupFilesDir, DIAGNOSTICS_DIRECTORY),
            persistenceEnabled = ::isPersistenceEnabled,
        )
    }

    fun submit(event: SanitizedStructuredDiagnosticEvent) {
        if (!isPersistenceEnabled()) return
        bestEffort { history.submit(event) }
    }

    fun snapshot(flushTimeoutMillis: Long = 2_000): String {
        if (!isPersistenceEnabled()) return ""
        return bestEffort { history.snapshot(flushTimeoutMillis = flushTimeoutMillis) }.getOrDefault("")
    }

    fun flush(timeoutMillis: Long = 2_000): Boolean {
        if (!isPersistenceEnabled()) return true
        return bestEffort { history.flush(timeoutMillis) }.getOrDefault(false)
    }

    fun clear(): Boolean = bestEffort { history.clear() }.getOrDefault(false)

    internal fun persistenceAllowed(): Boolean = isPersistenceEnabled()

    private fun isPersistenceEnabled(): Boolean = bestEffort {
        !basePreferences.incognitoMode.get()
    }.getOrDefault(false)

    private inline fun <T> bestEffort(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        const val DIAGNOSTICS_DIRECTORY = "tsuzuki/diagnostics"
    }
}
