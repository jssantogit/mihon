package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.BoundedLogcatCollector
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.CrashLogReportComposer
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.DiagnosticRecorderHealth
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.LocalStructuredDiagnosticHistory
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.StructuredDiagnosticSummary
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.LogcatCapture
import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.LogcatFailure
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import kotlin.time.Clock

@Inject
class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val preferences: BasePreferences,
    private val networkPreferences: NetworkPreferences,
    private val structuredHistory: LocalStructuredDiagnosticHistory,
    private val diagnosticRecorderHealth: DiagnosticRecorderHealth,
    private val logcatCollector: BoundedLogcatCollector,
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val debugInfo = getDebugInfo()
            val extensionsInfo = getExtensionsInfo()
            val exceptionText = exception?.toString()
            val reportFile = withContext(Dispatchers.IO) {
                val flushed = structuredHistory.flush(timeoutMillis = 500)
                if (!flushed) diagnosticRecorderHealth.exportFlushTimedOut()
                val history = structuredHistory.snapshot(flushTimeoutMillis = 0)
                val diagnosticSummary = StructuredDiagnosticSummary.build(
                    structuredHistory = history,
                    health = diagnosticRecorderHealth.snapshot(),
                )
                val logPriority = if (networkPreferences.verboseLogging.get()) "V" else "E"
                val logcat = try {
                    logcatCollector.collect(logPriority)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    LogcatCapture.unavailable(LogcatFailure.START_FAILED)
                }
                val report = CrashLogReportComposer.compose(
                    debugInfo = debugInfo,
                    extensionsInfo = extensionsInfo,
                    exception = exceptionText,
                    diagnosticSummary = diagnosticSummary,
                    structuredHistory = history,
                    logcat = logcat,
                )
                context.createFileInCacheDir("mihon_crash_logs.txt").apply { writeText(report) }
            }

            val uri = reportFile.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Installation ID: ${preferences.installationId.get()}
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${now.toLocalDateTime(tz)}${tz.offsetAt(now)}
        """.trimIndent()
    }

    private suspend fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val extensionInfoList = extensionManager.getInstalledExtensions()
            .sortedBy { it.name }
            .mapNotNull {
                val availableExtension = availableExtensions[it.pkgName]
                val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                """
                    - ${it.name}
                      Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                      Orphaned: ${it.isObsolete}
                """.trimIndent()
            }

        return if (extensionInfoList.isNotEmpty()) {
            (listOf("Problematic extensions:") + extensionInfoList)
                .joinToString("\n")
        } else {
            null
        }
    }
}
