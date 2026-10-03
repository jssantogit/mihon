package tachiyomi.core.provider.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class ProviderRuntimeLimits(
    val wallClockTimeoutMs: Long = 5_000L,
    val jsExecutionTimeoutMs: Long = 3_000L,
    val memoryLimitBytes: Long = 32L * 1024L * 1024L,
    val stackLimitBytes: Long = 512L * 1024L,
) {
    init {
        require(wallClockTimeoutMs > 0)
        require(jsExecutionTimeoutMs > 0)
        require(memoryLimitBytes > 0)
        require(stackLimitBytes > 0)
    }
}

enum class ProviderScriptFailure {
    TIMEOUT,
    SCRIPT_ERROR,
    HOST_ERROR,
}

sealed interface ProviderScriptExecution {
    data class Success(val value: String?) : ProviderScriptExecution
    data class Failure(val reason: ProviderScriptFailure) : ProviderScriptExecution
}

class ProviderQuickJsRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val limits: ProviderRuntimeLimits = ProviderRuntimeLimits(),
) {

    suspend fun evaluate(
        source: String,
        fileName: String = "provider.js",
    ): ProviderScriptExecution {
        val runtime = QuickJs.create(dispatcher)
        return try {
            runtime.memoryLimit = limits.memoryLimitBytes
            runtime.maxStackSize = limits.stackLimitBytes
            runtime.evaluationTimeoutMillis = limits.jsExecutionTimeoutMs

            val value = withTimeout(limits.wallClockTimeoutMs) {
                runtime.evaluate<Any?>(
                    code = source,
                    filename = fileName,
                    asModule = false,
                )
            }
            ProviderScriptExecution.Success(value?.toString())
        } catch (_: QuickJsInterruptedException) {
            ProviderScriptExecution.Failure(ProviderScriptFailure.TIMEOUT)
        } catch (_: TimeoutCancellationException) {
            runtime.interruptEvaluation()
            ProviderScriptExecution.Failure(ProviderScriptFailure.TIMEOUT)
        } catch (_: QuickJsException) {
            ProviderScriptExecution.Failure(ProviderScriptFailure.SCRIPT_ERROR)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            ProviderScriptExecution.Failure(ProviderScriptFailure.HOST_ERROR)
        } finally {
            runtime.close()
        }
    }
}
