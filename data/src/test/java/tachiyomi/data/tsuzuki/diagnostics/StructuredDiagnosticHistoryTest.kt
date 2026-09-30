package tachiyomi.data.tsuzuki.diagnostics

import io.kotest.matchers.shouldBe
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
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        snapshot.contains("source_search_completed") shouldBe true
        snapshot.contains("schemaVersion") shouldBe true
        snapshot.contains("Authorization") shouldBe false
        snapshot.contains("secret-token") shouldBe false
        reopened.close()
    }

    @Test
    fun `expired segments are removed across reopened instances`() {
        val directory = temporaryDirectory.resolve("history").toFile()
        val maxAgeMillis = 3L * 24 * 60 * 60 * 1_000
        val cap = maxBytesForTwoRecords()

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
                snapshot.contains("\"timestampMillis\":259210001") shouldBe true
                snapshot.contains("\"timestampMillis\":10000") shouldBe false
            }
    }

    @Test
    fun `snapshot removes expired records without a new submission`() {
        val directory = temporaryDirectory.resolve("retention-without-write").toFile()
        val maxAgeMillis = 3L * 24 * 60 * 60 * 1_000
        val original = history(directory, now = 10_000L, maxAgeMillis = maxAgeMillis)
        original.submit(safeEvent(timestamp = 10_000L))
        original.flush() shouldBe true
        original.close()

        val reopened = history(directory, now = 10_000L + maxAgeMillis + 1, maxAgeMillis = maxAgeMillis)
        reopened.snapshot() shouldBe ""
        reopened.close()
    }

    @Test
    fun `retention removes empty instance directories across reopen cycles`() {
        val directory = temporaryDirectory.resolve("empty-instance-cleanup").toFile()

        repeat(20) { index ->
            val now = index * 1_000L
            val reopened = history(directory, now = now, maxAgeMillis = 500L, maxBytes = 700L)
            reopened.submit(safeEvent(timestamp = now))
            reopened.flush() shouldBe true
            reopened.close()
        }

        directory.listFiles { file -> file.isDirectory && file.name.startsWith("instance-") }
            .orEmpty()
            .size shouldBe 1
    }

    @Test
    fun `byte cap evicts oldest records across instance directories`() {
        val directory = temporaryDirectory.resolve("byte-cap").toFile()
        val cap = maxBytesForTwoRecords()
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
        snapshot.contains("\"timestampMillis\":101") shouldBe false
        snapshot.contains("\"timestampMillis\":301") shouldBe true
        val retainedBytes = directory.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .sumOf { it.length() }
        (retainedBytes <= cap) shouldBe true
        third.close()
    }

    @Test
    fun `byte cap preserves newest records when one instance rotates segments`() {
        val directory = temporaryDirectory.resolve("same-instance-byte-cap").toFile()
        val cap = maxBytesForTwoRecords()
        val history = history(directory, now = 100L, maxBytes = cap)

        history.submit(safeEvent(timestamp = 101))
        history.submit(safeEvent(timestamp = 102))
        history.submit(safeEvent(timestamp = 103))

        history.flush() shouldBe true
        val snapshot = history.snapshot()
        snapshot.contains("\"timestampMillis\":101") shouldBe false
        snapshot.contains("\"timestampMillis\":103") shouldBe true
        val retainedBytes = directory.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .sumOf { it.length() }
        (retainedBytes <= cap) shouldBe true
        history.close()
    }

    @Test
    fun `oldest-first cap eviction works across interleaved live instances`() {
        val directory = temporaryDirectory.resolve("interleaved-instances").toFile()
        val clock = AtomicLong(100L)
        val cap = maxBytesForTwoRecords()
        val first = history(directory, maxBytes = cap, clockMillis = clock::get)
        val second = history(directory, maxBytes = cap, clockMillis = clock::get)

        first.submit(safeEvent(timestamp = 100L))
        first.flush() shouldBe true
        clock.set(200L)
        second.submit(safeEvent(timestamp = 200L))
        second.flush() shouldBe true
        clock.set(300L)
        first.submit(safeEvent(timestamp = 300L))
        first.flush() shouldBe true

        val snapshot = first.snapshot()
        snapshot.contains("\"timestampMillis\":100") shouldBe false
        snapshot.contains("\"timestampMillis\":200") shouldBe true
        snapshot.contains("\"timestampMillis\":300") shouldBe true
        first.close()
        second.close()
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
        snapshot.contains("dropped_events") shouldBe true
        snapshot.contains("\"count\":2") shouldBe true
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
        val enabled = AtomicBoolean(false)
        val disabledAtSubmission = history(directory, persistenceEnabled = enabled::get)
        disabledAtSubmission.submit(safeEvent(timestamp = 1))
        disabledAtSubmission.flush() shouldBe true
        disabledAtSubmission.snapshot() shouldBe ""
        disabledAtSubmission.close()

        enabled.set(true)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val executor = singleThreadExecutor(queueCapacity = 2)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        blockerStarted.await(5, TimeUnit.SECONDS) shouldBe true
        val caller = Thread.currentThread()
        val writerCheckedGate = CountDownLatch(1)
        val queued = history(
            directory,
            persistenceEnabled = {
                if (Thread.currentThread() != caller) writerCheckedGate.countDown()
                enabled.get()
            },
            executor = executor,
        )
        queued.submit(safeEvent(timestamp = 2))
        enabled.set(false)
        releaseBlocker.countDown()
        writerCheckedGate.await(5, TimeUnit.SECONDS) shouldBe true
        executor.submit(java.util.concurrent.Callable { Unit }).get(5, TimeUnit.SECONDS)

        queued.flush() shouldBe true
        queued.snapshot() shouldBe ""
        queued.close()
        executor.shutdownNow()
    }

    @Test
    fun `incognito enabled while writer waits for root lock prevents append`() {
        val directory = temporaryDirectory.resolve("incognito-lock-append").toFile().apply { mkdirs() }
        val lockChannel = FileChannel.open(
            directory.resolve(".structured-diagnostics.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val heldLock = lockChannel.lock()
        val caller = Thread.currentThread()
        val writerStarted = CountDownLatch(1)
        val writerCheckedGate = CountDownLatch(1)
        val writerGateChecks = AtomicInteger(0)
        val enabled = AtomicBoolean(true)
        val executor = singleThreadExecutor(queueCapacity = 2)
        val history = history(
            directory,
            persistenceEnabled = {
                val enabledAtCheck = enabled.get()
                if (Thread.currentThread() != caller) {
                    when (writerGateChecks.incrementAndGet()) {
                        1 -> writerStarted.countDown()
                        2 -> writerCheckedGate.countDown()
                    }
                }
                enabledAtCheck
            },
            executor = executor,
            lockTimeoutMillis = 2_000L,
        )

        try {
            history.submit(safeEvent(timestamp = 1))
            writerStarted.await(5, TimeUnit.SECONDS) shouldBe true
            enabled.set(false)
        } finally {
            heldLock.release()
            lockChannel.close()
        }

        writerCheckedGate.await(5, TimeUnit.SECONDS) shouldBe true
        executor.submit(java.util.concurrent.Callable { Unit }).get(5, TimeUnit.SECONDS)
        writerGateChecks.get() shouldBe 2
        directory.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.count() shouldBe 0
        history.close()
        executor.shutdownNow()
    }

    @Test
    fun `incognito enabled while snapshot waits for root lock returns no history`() {
        val directory = temporaryDirectory.resolve("incognito-lock-snapshot").toFile().apply { mkdirs() }
        val enabled = AtomicBoolean(true)
        val snapshotThread = java.util.concurrent.atomic.AtomicReference<Thread?>()
        val snapshotCheckedGate = CountDownLatch(1)
        val snapshotGateChecks = AtomicInteger(0)
        val history = history(
            directory,
            persistenceEnabled = {
                val enabledAtCheck = enabled.get()
                if (Thread.currentThread() == snapshotThread.get() && snapshotGateChecks.incrementAndGet() == 2) {
                    snapshotCheckedGate.countDown()
                }
                enabledAtCheck
            },
            lockTimeoutMillis = 2_000L,
        )
        history.submit(safeEvent(timestamp = 1))
        history.flush() shouldBe true

        val lockChannel = FileChannel.open(
            directory.resolve(".structured-diagnostics.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val heldLock = lockChannel.lock()
        val result = java.util.concurrent.FutureTask<String> { history.snapshot() }
        val snapshotWorker = Thread(result, "structured-diagnostic-snapshot-test").apply { isDaemon = true }
        snapshotThread.set(snapshotWorker)
        snapshotWorker.start()
        try {
            snapshotCheckedGate.await(5, TimeUnit.SECONDS) shouldBe true
            enabled.set(false)
        } finally {
            heldLock.release()
            lockChannel.close()
        }

        result.get(5, TimeUnit.SECONDS) shouldBe ""
        snapshotGateChecks.get() shouldBe 3
        history.close()
    }

    @Test
    fun `writer failure is recorded and later events still persist`() {
        val directory = temporaryDirectory.resolve("writer-failure").toFile()
        val failClock = AtomicBoolean(true)
        val history = history(directory, clockMillis = {
            if (failClock.get()) throw IllegalStateException("clock unavailable")
            100L
        })

        history.submit(safeEvent(timestamp = 1))
        history.flush() shouldBe false
        failClock.set(false)
        history.submit(safeEvent(timestamp = 2))
        history.flush() shouldBe true

        val snapshot = history.snapshot()
        snapshot.contains("dropped_events") shouldBe true
        snapshot.contains("\"count\":1") shouldBe true
        snapshot.contains("\"timestampMillis\":2") shouldBe true
        history.close()
    }

    @Test
    fun `snapshot accepts only supported history schema and record types`() {
        val directory = temporaryDirectory.resolve("schema-filter").toFile()
        val instanceDirectory = directory.resolve("instance-fixture").apply { mkdirs() }
        val segment = instanceDirectory.resolve("segment-000000-0000000000100-0000.jsonl")
        segment.writeText(
            listOf(
                """{"schemaVersion":1,"recordType":"event","marker":"supported_event"}""",
                """{"schemaVersion":1,"recordType":"dropped_events","marker":"supported_drop"}""",
                """{"schemaVersion":null,"recordType":null,"marker":"null_markers"}""",
                """{"schemaVersion":2,"recordType":"event","marker":"unknown_version"}""",
                """{"schemaVersion":1,"recordType":"future_record","marker":"unknown_type"}""",
                """{"schemaVersion":"1","recordType":"event","marker":"string_version"}""",
                """{"schemaVersion":1,"recordType":7,"marker":"numeric_type"}""",
                """{"schemaVersion":1,"recordType":"event","marker":"truncated""",
            ).joinToString("\n"),
        )
        val history = history(directory, now = 100L)

        val snapshot = history.snapshot()

        snapshot.contains("supported_event") shouldBe true
        snapshot.contains("supported_drop") shouldBe true
        snapshot.contains("null_markers") shouldBe false
        snapshot.contains("unknown_version") shouldBe false
        snapshot.contains("unknown_type") shouldBe false
        snapshot.contains("string_version") shouldBe false
        snapshot.contains("numeric_type") shouldBe false
        snapshot.contains("truncated") shouldBe false
        history.close()
    }

    private fun history(
        directory: java.io.File,
        now: Long = 1_000L,
        maxAgeMillis: Long = 3L * 24 * 60 * 60 * 1_000,
        maxBytes: Long = 5L * 1024 * 1024,
        persistenceEnabled: () -> Boolean = { true },
        executor: ThreadPoolExecutor? = null,
        clockMillis: () -> Long = { now },
        lockTimeoutMillis: Long = 250L,
    ): StructuredDiagnosticHistory = if (executor == null) {
        StructuredDiagnosticHistory(
            directory = directory,
            persistenceEnabled = persistenceEnabled,
            writerExecutor = singleThreadExecutor(queueCapacity = 8),
            ownsWriterExecutor = true,
            maxBytes = maxBytes,
            maxAgeMillis = maxAgeMillis,
            clockMillis = clockMillis,
            lockTimeoutMillis = lockTimeoutMillis,
        )
    } else {
        StructuredDiagnosticHistory(
            directory = directory,
            persistenceEnabled = persistenceEnabled,
            writerExecutor = executor,
            ownsWriterExecutor = false,
            maxBytes = maxBytes,
            maxAgeMillis = maxAgeMillis,
            clockMillis = clockMillis,
            lockTimeoutMillis = lockTimeoutMillis,
        )
    }

    private fun singleThreadExecutor(queueCapacity: Int) = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(queueCapacity),
        { runnable -> Thread(runnable, "structured-diagnostic-history-test").apply { isDaemon = true } },
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
                attributes = mapOf(
                    "candidate_count" to DiagnosticAttributeValue.Number(2),
                    "authorization" to DiagnosticAttributeValue.Text("secret-token"),
                    "search_text" to DiagnosticAttributeValue.Text("private search phrase"),
                    "url" to DiagnosticAttributeValue.Text("https://example.invalid/private"),
                ),
            ),
        ),
    )

    private fun maxBytesForTwoRecords(): Long =
        (StructuredDiagnosticHistoryJson.serialize(safeEvent(timestamp = 100L)).toByteArray().size + 1L) * 2L
}
