package tachiyomi.data.tsuzuki.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldContain
import io.kotest.matchers.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class StructuredDiagnosticHistoryTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `only sanitized events are persisted and history survives reopening`() {
        val directory = temporaryDirectory.resolve("history").toFile()
        val history = history(directory)

        history.submit(safeEvent(timestamp = 1_000L))
        history.flush() shouldBe true
        history.close()

        val reopened = history(directory)
        val snapshot = reopened.snapshot()
        snapshot shouldContain "source_search_completed"
        snapshot shouldContain "schemaVersion"
        snapshot shouldNotContain "Authorization"
        snapshot shouldNotContain "secret-token"
        reopened.close()
    }

    @Test
    fun `expired segments are removed across reopened instances`() {
        val directory = temporaryDirectory.resolve("history").toFile()
        val maxAgeMillis = 3L * 24 * 60 * 60 * 1_000
        val cap = 900L

        val old = history(directory, now = 10_000L, maxAgeMillis = maxAgeMillis, maxBytes = cap)
        old.submit(safeEvent(timestamp = 10_000L))
        old.flush() shouldBe true
        old.close()

        val retained = history(directory, now = 10_000L + maxAgeMillis + 1, maxAgeMillis = maxAgeMillis, maxBytes = cap)
        retained.submit(safeEvent(timestamp = 10_000L + maxAgeMillis + 1))
        retained.flush() shouldBe true
        retained.close()
        history(directory, now = 10_000L + maxAgeMillis + 1, maxAgeMillis = maxAgeMillis, maxBytes = cap)
            .use { reopened ->
                val snapshot = reopened.snapshot()
                snapshot shouldContain "\"timestampMillis\":259210001"
                snapshot shouldNotContain "\"timestampMillis\":10000"
            }
    }

    @Test
    fun `byte cap evicts oldest records across instance directories`() {
        val directory = temporaryDirectory.resolve("byte-cap").toFile()
        val cap = 900L
        val first = history(directory, now = 100L, maxBytes = cap)
        first.submit(safeEvent(timestamp = 101))
        first.flush() shouldBe true
        first.close()

        val second = history(directory, now = 200L, maxBytes = cap)
        second.submit(safeEvent(timestamp = 201))
        second.flush() shouldBe true
        second.close()

        val third = history(directory, now = 300L, maxBytes = cap)
        third.submit(safeEvent(timestamp = 301))
        third.flush() shouldBe true
        val snapshot = third.snapshot()
        snapshot shouldNotContain "\"timestampMillis\":101"
        snapshot shouldContain "\"timestampMillis\":301"
        directory.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.sumOf { it.length() } <= cap shouldBe true
        third.close()
    }

    @Test
    fun `queue saturation is nonfatal and records dropped event count`() {
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val executor = singleThreadExecutor(queueCapacity = 1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        blockerStarted.await(5, TimeUnit.SECONDS) shouldBe true
        val history = history(temporaryDirectory.resolve("overflow").toFile(), executor = executor)

        history.submit(safeEvent(timestamp = 10))
        history.submit(safeEvent(timestamp = 11))
        history.submit(safeEvent(timestamp = 12))
        releaseBlocker.countDown()

        history.flush() shouldBe true
        val snapshot = history.snapshot()
        snapshot shouldContain "dropped_events"
        snapshot shouldContain "\"count\":2"
        history.close()
        executor.shutdownNow()
    }

    @Test
    fun `storage failure does not escape submission flush or export`() {
        val blockedFile = temporaryDirectory.resolve("not-a-directory").toFile().apply { writeText("file") }
        val history = history(blockedFile.resolve("history"))

        history.submit(safeEvent(timestamp = 1))
        history.flush() shouldBe false
        history.snapshot() shouldBe ""
        history.close()
    }

    @Test
    fun `incognito disables persistence at submission and when queued work drains`() {
        val directory = temporaryDirectory.resolve("incognito").toFile()
        var enabled = false
        val disabledAtSubmission = history(directory, persistenceEnabled = { enabled })
        disabledAtSubmission.submit(safeEvent(timestamp = 1))
        disabledAtSubmission.flush() shouldBe true
        disabledAtSubmission.snapshot() shouldBe ""
        disabledAtSubmission.close()

        enabled = true
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val executor = singleThreadExecutor(queueCapacity = 2)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        blockerStarted.await(5, TimeUnit.SECONDS) shouldBe true
        val queued = history(directory, persistenceEnabled = { enabled }, executor = executor)
        queued.submit(safeEvent(timestamp = 2))
        enabled = false
        releaseBlocker.countDown()

        queued.flush() shouldBe true
        queued.snapshot() shouldBe ""
        queued.close()
        executor.shutdownNow()
    }

    private fun history(
        directory: java.io.File,
        now: Long = 1_000L,
        maxAgeMillis: Long = 3L * 24 * 60 * 60 * 1_000,
        maxBytes: Long = 5L * 1024 * 1024,
        persistenceEnabled: () -> Boolean = { true },
        executor: ThreadPoolExecutor? = null,
    ): StructuredDiagnosticHistory = if (executor == null) {
        StructuredDiagnosticHistory(
            directory = directory,
            persistenceEnabled = persistenceEnabled,
            writerExecutor = singleThreadExecutor(queueCapacity = 8),
            ownsWriterExecutor = true,
            maxBytes = maxBytes,
            maxAgeMillis = maxAgeMillis,
            clockMillis = { now },
        )
    } else {
        StructuredDiagnosticHistory(
            directory = directory,
            persistenceEnabled = persistenceEnabled,
            writerExecutor = executor,
            ownsWriterExecutor = false,
            maxBytes = maxBytes,
            maxAgeMillis = maxAgeMillis,
            clockMillis = { now },
        )
    }

    private fun singleThreadExecutor(queueCapacity: Int) = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(queueCapacity),
    )

    private fun safeEvent(timestamp: Long) = requireNotNull(
        StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = timestamp,
                severity = DiagnosticSeverity.WARN,
                subsystem = DiagnosticSubsystem.SOURCE,
                name = DiagnosticEventName.SOURCE_SEARCH_COMPLETED,
                sessionId = "123e4567-e89b-12d3-a456-426614174000",
                operationId = "123e4567-e89b-12d3-a456-426614174001",
                stage = DiagnosticStage.SEARCH,
                outcome = DiagnosticOutcome.CANDIDATES,
                attributes = mapOf("candidate_count" to DiagnosticAttributeValue.Number(2)),
            ),
        ),
    )
}
