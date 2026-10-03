package eu.kanade.tachiyomi.provider.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tachiyomi.core.provider.runtime.ProviderQuickJsRuntime
import tachiyomi.core.provider.runtime.ProviderRuntimeLimits
import tachiyomi.core.provider.runtime.ProviderScriptExecution

class ProviderRuntimeService : Service() {

    private val binder = object : IProviderRuntimeService.Stub() {

        override fun evaluate(
            source: String?,
            wallClockTimeoutMs: Long,
            jsExecutionTimeoutMs: Long,
        ): String {
            val limits = ProviderRuntimeLimits(
                wallClockTimeoutMs = wallClockTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
                jsExecutionTimeoutMs = jsExecutionTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
            )
            val result = runBlocking {
                ProviderQuickJsRuntime(
                    dispatcher = Dispatchers.Default,
                    limits = limits,
                ).evaluate(source.orEmpty())
            }
            return when (result) {
                is ProviderScriptExecution.Success -> "ok:${result.value.orEmpty()}"
                is ProviderScriptExecution.Failure -> "error:${result.reason.name}"
            }
        }

        override fun processUid(): Int = Process.myUid()

        override fun processPid(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val MIN_TIMEOUT_MS = 25L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}
