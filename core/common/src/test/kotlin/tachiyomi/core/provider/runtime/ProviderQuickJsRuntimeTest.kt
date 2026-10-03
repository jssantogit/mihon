package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProviderQuickJsRuntimeTest {

    @Test
    fun `evaluates primitive JavaScript result`() = runTest {
        val runtime = ProviderQuickJsRuntime()

        runtime.evaluate("1 + 2") shouldBe
            ProviderScriptExecution.Success("3")
    }

    @Test
    fun `interrupts runaway JavaScript`() = runTest {
        val runtime = ProviderQuickJsRuntime(
            limits = ProviderRuntimeLimits(
                wallClockTimeoutMs = 100,
                jsExecutionTimeoutMs = 50,
                memoryLimitBytes = 8L * 1024L * 1024L,
                stackLimitBytes = 256L * 1024L,
            ),
        )

        runtime.evaluate("while (true) {}") shouldBe
            ProviderScriptExecution.Failure(ProviderScriptFailure.TIMEOUT)
    }

    @Test
    fun `reports script errors without crashing host`() = runTest {
        val runtime = ProviderQuickJsRuntime()

        runtime.evaluate("throw new Error('boom')") shouldBe
            ProviderScriptExecution.Failure(ProviderScriptFailure.SCRIPT_ERROR)
    }
}
