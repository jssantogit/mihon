package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage

class InMemoryChapterInventoryDiagnosticsTest {

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
        stage = ChapterInventoryDiagnosticStage.INVENTORY,
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
