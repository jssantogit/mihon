package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrashLogReportComposerTest {
    @Test
    fun `composes structured history and keeps report usable when logcat is unavailable`() {
        val report = CrashLogReportComposer.compose(
            debugInfo = "debug info",
            extensionsInfo = "extensions",
            exception = "explicit exception",
            diagnosticSummary = "summary",
            structuredHistory = "{\"schemaVersion\":1,\"recordType\":\"event\"}",
            logcat = LogcatCapture.unavailable(LogcatFailure.TIMEOUT),
        )

        assertTrue(report.contains("debug info"))
        assertTrue(report.contains("extensions"))
        assertTrue(report.contains("explicit exception"))
        assertTrue(report.contains("Diagnostic summary"))
        assertTrue(report.contains("Structured diagnostic history"))
        assertTrue(report.contains("Logcat collection partial or unavailable: timeout"))
    }

    @Test
    fun `composes partial logcat output and marks nonzero exit`() {
        val report = CrashLogReportComposer.compose(
            debugInfo = "debug info",
            extensionsInfo = null,
            exception = null,
            diagnosticSummary = "summary",
            structuredHistory = "history-line",
            logcat = LogcatCapture.partial("partial logcat", LogcatFailure.EXIT_CODE),
        )

        assertTrue(report.contains("partial logcat"))
        assertTrue(report.contains("Logcat collection partial or unavailable: exit_code"))
        assertFalse(report.contains("null"))
    }

    @Test
    fun `composes every fixed marker when output is truncated and exit is nonzero`() {
        val report = CrashLogReportComposer.compose(
            debugInfo = "debug info",
            extensionsInfo = null,
            exception = null,
            diagnosticSummary = "summary",
            structuredHistory = "history-line",
            logcat = LogcatCapture.partial(
                "partial logcat",
                listOf(LogcatFailure.EXIT_CODE, LogcatFailure.OUTPUT_TRUNCATED),
            ),
        )

        assertTrue(report.contains("Logcat collection partial or unavailable: exit_code, output_truncated"))
    }
}
