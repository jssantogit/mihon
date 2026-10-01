package eu.kanade.tachiyomi.crash

import android.content.Context
import android.content.Intent
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.system.exitProcess

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    private val activityToBeLaunched: Class<*>,
    private val crashLogStore: PersistentCrashLogStore,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        crashLogStore.record(thread, exception)
        runCatching {
            logcat(priority = LogPriority.ERROR, throwable = exception)
        }
        runCatching {
            launchActivity(applicationContext, activityToBeLaunched, exception)
        }

        defaultHandler?.uncaughtException(thread, exception) ?: exitProcess(10)
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, exception.toString().take(MAX_CRASH_PREVIEW_CHARS))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"
        private const val MAX_CRASH_PREVIEW_CHARS = 16 * 1024

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            if (Thread.getDefaultUncaughtExceptionHandler() is GlobalExceptionHandler) return

            val handler = GlobalExceptionHandler(
                applicationContext = applicationContext,
                defaultHandler = Thread.getDefaultUncaughtExceptionHandler(),
                activityToBeLaunched = activityToBeLaunched,
                crashLogStore = PersistentCrashLogStore(applicationContext),
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? =
            intent.getStringExtra(INTENT_EXTRA)
                ?.takeIf(String::isNotBlank)
                ?.let(::Throwable)
    }
}
