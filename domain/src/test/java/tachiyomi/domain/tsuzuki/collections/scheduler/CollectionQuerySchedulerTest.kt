package tachiyomi.domain.tsuzuki.collections.scheduler

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CollectionQuerySchedulerTest {

    @Test
    fun `visible outranks next screen and background while preserving fifo within priority`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 1,
            maxConcurrentPerProvider = 1,
        )
        val blockerGate = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()

        val blocker = scheduler.schedule("provider", QuerySchedulePriority.VISIBLE) {
            starts += "blocker"
            blockerGate.await()
            "blocker"
        }
        runCurrent()

        val backgroundFirst = scheduler.schedule("provider", QuerySchedulePriority.BACKGROUND) {
            starts += "background-1"
            "background-1"
        }
        val nextScreen = scheduler.schedule("provider", QuerySchedulePriority.NEXT_SCREEN) {
            starts += "next"
            "next"
        }
        val visible = scheduler.schedule("provider", QuerySchedulePriority.VISIBLE) {
            starts += "visible"
            "visible"
        }
        val backgroundSecond = scheduler.schedule("provider", QuerySchedulePriority.BACKGROUND) {
            starts += "background-2"
            "background-2"
        }

        runCurrent()
        starts shouldContainExactly listOf("blocker")

        blockerGate.complete(Unit)
        runCurrent()

        blocker.await() shouldBe "blocker"
        visible.await() shouldBe "visible"
        nextScreen.await() shouldBe "next"
        backgroundFirst.await() shouldBe "background-1"
        backgroundSecond.await() shouldBe "background-2"

        starts shouldContainExactly listOf(
            "blocker",
            "visible",
            "next",
            "background-1",
            "background-2",
        )
    }

    @Test
    fun `global concurrency remains bounded`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 2,
            maxConcurrentPerProvider = 2,
        )
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxObserved = 0

        suspend fun work(label: String): String {
            active++
            maxObserved = maxOf(maxObserved, active)
            release.await()
            active--
            return label
        }

        val first = scheduler.schedule("a", QuerySchedulePriority.VISIBLE) { work("a") }
        val second = scheduler.schedule("b", QuerySchedulePriority.VISIBLE) { work("b") }
        val third = scheduler.schedule("c", QuerySchedulePriority.VISIBLE) { work("c") }

        runCurrent()

        maxObserved shouldBe 2
        scheduler.snapshot().runningCount shouldBe 2
        scheduler.snapshot().queuedCount shouldBe 1

        release.complete(Unit)
        runCurrent()

        first.await() shouldBe "a"
        second.await() shouldBe "b"
        third.await() shouldBe "c"
        maxObserved shouldBe 2
    }

    @Test
    fun `per provider limit does not block another provider`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 2,
            maxConcurrentPerProvider = 1,
        )
        val firstProviderGate = CompletableDeferred<Unit>()
        val otherProviderGate = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()

        val a1 = scheduler.schedule("a", QuerySchedulePriority.VISIBLE) {
            starts += "a1"
            firstProviderGate.await()
            "a1"
        }
        val a2 = scheduler.schedule("a", QuerySchedulePriority.VISIBLE) {
            starts += "a2"
            "a2"
        }
        val b1 = scheduler.schedule("b", QuerySchedulePriority.VISIBLE) {
            starts += "b1"
            otherProviderGate.await()
            "b1"
        }

        runCurrent()

        starts shouldContainExactly listOf("a1", "b1")
        scheduler.snapshot().runningByProvider shouldBe mapOf("a" to 1, "b" to 1)

        firstProviderGate.complete(Unit)
        runCurrent()

        a1.await() shouldBe "a1"
        a2.await() shouldBe "a2"
        starts shouldContainExactly listOf("a1", "b1", "a2")

        otherProviderGate.complete(Unit)
        runCurrent()
        b1.await() shouldBe "b1"
    }

    @Test
    fun `queued request can be cancelled before execution`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 1,
            maxConcurrentPerProvider = 1,
        )
        val blockerGate = CompletableDeferred<Unit>()
        var cancelledTaskRan = false

        val blocker = scheduler.schedule("provider", QuerySchedulePriority.VISIBLE) {
            blockerGate.await()
            "blocker"
        }
        runCurrent()

        val queued = scheduler.schedule("provider", QuerySchedulePriority.BACKGROUND) {
            cancelledTaskRan = true
            "queued"
        }
        runCurrent()

        queued.cancel() shouldBe true
        queued.result.isCancelled shouldBe true

        blockerGate.complete(Unit)
        runCurrent()
        blocker.await() shouldBe "blocker"
        cancelledTaskRan shouldBe false
    }

    @Test
    fun `running request cancellation reaches worker block`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 1,
            maxConcurrentPerProvider = 1,
        )
        var finallyReached = false

        val running = scheduler.schedule("provider", QuerySchedulePriority.VISIBLE) {
            try {
                awaitCancellation()
            } finally {
                finallyReached = true
            }
        }

        runCurrent()
        scheduler.snapshot().runningCount shouldBe 1

        running.cancel() shouldBe true
        runCurrent()

        running.result.isCancelled shouldBe true
        finallyReached shouldBe true
        scheduler.snapshot().runningCount shouldBe 0
    }

    @Test
    fun `queued request can be reprioritized after viewport changes`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 1,
            maxConcurrentPerProvider = 1,
        )
        val blockerGate = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()

        val blocker = scheduler.schedule("provider", QuerySchedulePriority.VISIBLE) {
            blockerGate.await()
            "blocker"
        }
        runCurrent()

        val first = scheduler.schedule("provider", QuerySchedulePriority.NEXT_SCREEN) {
            starts += "first"
            "first"
        }
        val promoted = scheduler.schedule("provider", QuerySchedulePriority.BACKGROUND) {
            starts += "promoted"
            "promoted"
        }

        scheduler.reprioritize(promoted.id, QuerySchedulePriority.VISIBLE) shouldBe true

        blockerGate.complete(Unit)
        runCurrent()

        blocker.await() shouldBe "blocker"
        promoted.await() shouldBe "promoted"
        first.await() shouldBe "first"
        starts shouldContainExactly listOf("promoted", "first")
    }

    @Test
    fun `reprioritizing or cancelling an unknown request is a no-op`() = runTest {
        val scheduler = CollectionQueryScheduler(
            scope = backgroundScope,
            maxConcurrent = 1,
            maxConcurrentPerProvider = 1,
        )

        scheduler.reprioritize(999, QuerySchedulePriority.VISIBLE) shouldBe false
        scheduler.cancel(999) shouldBe false
    }
}
