package eu.kanade.tachiyomi.crash

import android.content.Context
import java.io.File
import kotlin.time.Clock

/**
 * Minimal persistent crash journal that intentionally has no dependency on the application graph.
 *
 * It is safe to construct before dependency injection so crashes during application bootstrap still
 * leave a shareable record for the next process.
 */
class PersistentCrashLogStore internal constructor(
    private val directory: File,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    constructor(context: Context) : this(
        directory = File(context.noBackupFilesDir, CRASH_DIRECTORY),
    )

    fun record(thread: Thread, exception: Throwable) {
        bestEffort {
            directory.mkdirs()
            val report = buildString {
                appendLine("Tsuzuki persistent crash record")
                appendLine("timestamp_ms=${clockMillis().coerceAtLeast(0)}")
                appendLine("thread=${thread.name.take(MAX_THREAD_NAME_CHARS)}")
                appendLine()
                append(exception.stackTraceToString().take(MAX_REPORT_CHARS))
            }
            val target = File(directory, CRASH_FILE)
            val temporary = File(directory, "$CRASH_FILE.tmp")
            temporary.writeText(report)
            if (!temporary.renameTo(target)) {
                target.writeText(report)
                temporary.delete()
            }
        }
    }

    fun read(): String? = bestEffort {
        File(directory, CRASH_FILE)
            .takeIf(File::isFile)
            ?.readText()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun clear(): Boolean = bestEffort {
        val target = File(directory, CRASH_FILE)
        val temporary = File(directory, "$CRASH_FILE.tmp")
        val removedTarget = !target.exists() || target.delete()
        val removedTemporary = !temporary.exists() || temporary.delete()
        removedTarget && removedTemporary
    }.getOrDefault(false)

    private inline fun <T> bestEffort(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (_: Throwable) {
        Result.failure(IllegalStateException("Persistent crash storage unavailable"))
    }

    private companion object {
        const val CRASH_DIRECTORY = "tsuzuki/crash"
        const val CRASH_FILE = "last_uncaught_crash.txt"
        const val MAX_REPORT_CHARS = 256 * 1024
        const val MAX_THREAD_NAME_CHARS = 128
    }
}
