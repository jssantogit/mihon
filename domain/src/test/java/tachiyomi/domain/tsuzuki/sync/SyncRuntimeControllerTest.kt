package tachiyomi.domain.tsuzuki.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncCycleRunner
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeController
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger

class SyncRuntimeControllerTest {

    @Test
    fun `case 1 - manual run exposes running then completed diagnostics`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val report = SyncCycleReport(emptyList())
        val clock = FakeClock(100, 200)
        val controller = SyncRuntimeController(
            runner = SyncCycleRunner {
                entered.complete(Unit)
                release.await()
                report
            },
            clock = clock,
        )

        val run = async { controller.run(SyncTrigger.MANUAL) }
        entered.await()

        controller.state.value shouldBe
            SyncRuntimeState.Running(
                trigger = SyncTrigger.MANUAL,
                startedAtEpochMillis = 100,
            )

        release.complete(Unit)
        run.await() shouldBe report
        controller.state.value shouldBe
            SyncRuntimeState.Completed(
                trigger = SyncTrigger.MANUAL,
                startedAtEpochMillis = 100,
                completedAtEpochMillis = 200,
                report = report,
            )
    }

    @Test
    fun `case 2 - typed sync failure remains inspectable in completed report`() = runTest {
        val report = SyncCycleReport(
            documentResults = emptyList(),
            globalFailure = SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
        )
        val controller = SyncRuntimeController(
            runner = SyncCycleRunner { report },
            clock = FakeClock(100, 200),
        )

        controller.run(SyncTrigger.BACKGROUND)

        (controller.state.value as SyncRuntimeState.Completed)
            .report.globalFailure?.reason shouldBe SyncFailureReason.AUTHORIZATION_REQUIRED
    }

    @Test
    fun `case 3 - unexpected runner exception becomes local failed diagnostic and still propagates`() = runTest {
        val controller = SyncRuntimeController(
            runner = SyncCycleRunner { error("boom") },
            clock = FakeClock(100, 200),
        )

        shouldThrow<IllegalStateException> {
            controller.run(SyncTrigger.BACKGROUND)
        }

        controller.state.value shouldBe
            SyncRuntimeState.Failed(
                trigger = SyncTrigger.BACKGROUND,
                startedAtEpochMillis = 100,
                completedAtEpochMillis = 200,
                failure = SyncFailure(SyncFailureReason.UNKNOWN),
            )
    }

    @Test
    fun `case 4 - cancellation resets transient running state`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val controller = SyncRuntimeController(
            runner = SyncCycleRunner {
                entered.complete(Unit)
                awaitCancellation()
            },
            clock = FakeClock(100),
        )

        val run = async { controller.run(SyncTrigger.MANUAL) }
        entered.await()
        run.cancelAndJoin()

        controller.state.value shouldBe SyncRuntimeState.Idle
    }

    private class FakeClock(
        vararg times: Long,
    ) : SyncClock {
        private val values = ArrayDeque(times.toList())

        override fun nowEpochMillis(): Long =
            values.removeFirstOrNull() ?: error("No fake clock value remaining")
    }
}
