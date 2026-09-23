package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import eu.kanade.tachiyomi.data.tsuzuki.diagnosticHttpStatus
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage

class InMemoryChapterInventoryDiagnosticsTest {

    @Test
    fun `HTTP status is extracted only from Mihon HTTP exception and valid status range`() {
        IllegalStateException("wrapper", HttpException(403)).diagnosticHttpStatus() shouldBe 403
        IllegalStateException("HTTP 403 private message").diagnosticHttpStatus() shouldBe null
        HttpException(999).diagnosticHttpStatus() shouldBe null
    }

    @Test
    fun `diagnostics are disabled by default and ignore events until explicitly started`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            sessionIdFactory = { "test-session" },
        )

        diagnostics.isRecording("canonical-title") shouldBe false
        diagnostics.record(event(label = "1"))
        diagnostics.report() shouldBe ""

        val sessionId = diagnostics.start("canonical-title")
        sessionId shouldBe "test-session"
        diagnostics.isRecording("canonical-title") shouldBe true
        diagnostics.isRecording("another-title") shouldBe false
        diagnostics.record(event(label = "1"))

        diagnostics.report().contains("test-session") shouldBe true
        diagnostics.report().contains("1") shouldBe true
    }

    @Test
    fun `report hashes canonical identity and excludes unsafe chapter labels`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            sessionIdFactory = { "safe-session" },
        )
        diagnostics.start("private-canonical-title-name")
        diagnostics.record(event().copy(httpStatus = 403))
        diagnostics.record(event().copy(httpStatus = 999))
        diagnostics.record(
            event(
                labels = listOf(
                    "1",
                    "1.5",
                    "https://example.invalid/chapter/secret-page",
                    "Chapter 234 private scanlation name",
                    "cookie=private-cookie",
                    "token=private-token",
                ),
            ),
        )

        val report = diagnostics.report()

        report.contains("private-canonical-title-name") shouldBe false
        report.contains("secret-page") shouldBe false
        report.contains("private scanlation name") shouldBe false
        report.contains("private-cookie") shouldBe false
        report.contains("private-token") shouldBe false
        report.contains("httpStatus=403") shouldBe true
        report.contains("httpStatus=999") shouldBe false
        report.contains("1") shouldBe true
        report.contains("1.5") shouldBe true
    }

    @Test
    fun `event ring keeps only the newest configured number of records`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            maxEvents = 3,
            maxReportBytes = 32_768,
            sessionIdFactory = { "bounded-session" },
        )
        diagnostics.start("title")

        (1..20).forEach { number ->
            diagnostics.record(event(label = (1_000 + number).toString()))
        }

        val report = diagnostics.report()
        report.contains("1020") shouldBe true
        report.contains("1001") shouldBe false
        report shouldNotBe ""
    }

    @Test
    fun `exported report stays within configured byte bound while retaining newest events`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            maxEvents = 100,
            maxReportBytes = 1_024,
            sessionIdFactory = { "bounded-session" },
        )
        diagnostics.start("title")
        (1..30).forEach { number ->
            diagnostics.record(event(label = (1_000 + number).toString()))
        }

        val report = diagnostics.report()
        (report.toByteArray(Charsets.UTF_8).size <= 1_024) shouldBe true
        report.contains("1030") shouldBe true
    }

    @Test
    fun `per addon failure summary survives detailed ring eviction`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            maxEvents = 2,
            maxReportBytes = 2_048,
            sessionIdFactory = { "summary-session" },
        )
        diagnostics.start("private-title")
        diagnostics.record(
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.BINDING_SEARCH,
                outcome = ChapterInventoryDiagnosticOutcome.NETWORK_ERROR,
                addonId = "mangafire",
                sourceId = 12L,
                attempt = 2,
                reasons = mapOf(ChapterInventoryDiagnosticReason.NETWORK_FAILURE to 1),
            ),
        )
        repeat(10) { diagnostics.record(event(label = (100 + it).toString())) }

        val report = diagnostics.report()
        report.contains("diagnostic v2") shouldBe true
        report.contains("SUMMARY|addonId=mangafire|stage=BINDING_SEARCH|outcome=NETWORK_ERROR" +
            "|reason=NETWORK_FAILURE|count=1") shouldBe true
        report.contains("attempt=2") shouldBe false
        report.contains("private-title") shouldBe false
    }

    @Test
    fun `MangaFire first blocker summary survives hundreds of MangaDex inventory successes`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            maxEvents = 10,
            maxReportBytes = 8_192,
            sessionIdFactory = { "capture-session" },
        )
        diagnostics.start("title")
        diagnostics.record(
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.BINDING_SEARCH,
                outcome = ChapterInventoryDiagnosticOutcome.NETWORK_ERROR,
                addonId = "mangafire",
                sourceId = 45L,
                availabilityBlocked = true,
                affectedSourceCount = 3,
                reasons = mapOf(ChapterInventoryDiagnosticReason.NETWORK_FAILURE to 1),
            ),
        )
        repeat(836) {
            diagnostics.record(
                ChapterInventoryDiagnosticEvent(
                    stage = ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY,
                    outcome = ChapterInventoryDiagnosticOutcome.SUCCESS,
                    addonId = "mangadex",
                    sourceId = 12L,
                    received = 1,
                    accepted = 1,
                ),
            )
        }

        val report = diagnostics.report()

        report.contains(
            "SUMMARY|addonId=mangafire|firstBlockingStage=BINDING_SEARCH" +
                "|outcome=NETWORK_ERROR|affectedSources=3|reason=NETWORK_FAILURE",
        ) shouldBe true
        report.contains("correlationId=capture-session|event=837") shouldBe true
        (report.toByteArray(Charsets.UTF_8).size <= 8_192) shouldBe true
        report.contains("ChapterInventoryDiagnosticEvent") shouldBe false
    }

    @Test
    fun `concurrent records retain bounded correlated report`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            maxEvents = 200,
            maxReportBytes = 32_768,
            sessionIdFactory = { "concurrent-session" },
        )
        diagnostics.start("title")

        val writers = (1..8).map { writer ->
            thread {
                repeat(100) { index ->
                    diagnostics.record(event(label = "${writer * 100 + index}"))
                }
            }
        }
        writers.forEach(Thread::join)

        val report = diagnostics.report()
        val eventLines = report.lines().filter {
            "|correlationId=concurrent-session|event=" in it
        }
        eventLines.isNotEmpty() shouldBe true
        (eventLines.size <= 200) shouldBe true
        eventLines.mapNotNull { it.substringAfter("|event=").substringBefore('|').toIntOrNull() }
            .distinct().size shouldBe eventLines.size
        (report.toByteArray(Charsets.UTF_8).size <= 32_768) shouldBe true
    }

    @Test
    fun `clear erases the report and disables the active recording`() {
        val diagnostics = InMemoryChapterInventoryDiagnostics(
            sessionIdFactory = { "test-session" },
        )
        diagnostics.start("title")
        diagnostics.record(event(label = "138"))

        diagnostics.clear()

        diagnostics.report() shouldBe ""
        diagnostics.isRecording("title") shouldBe false
        diagnostics.record(event(label = "139"))
        diagnostics.report() shouldBe ""
    }

    private fun event(
        label: String = "1",
        labels: List<String> = listOf(label),
    ) = ChapterInventoryDiagnosticEvent(
        stage = ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY,
        outcome = ChapterInventoryDiagnosticOutcome.SUCCESS,
        sourceId = 7L,
        addonId = "mangafire",
        language = "en",
        elapsedMillis = 12L,
        received = 1,
        accepted = 1,
        provisional = 0,
        discarded = 0,
        labels = labels,
        gaps = emptyList(),
        reasons = emptyMap<ChapterInventoryDiagnosticReason, Int>(),
    )
}
