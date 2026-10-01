package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticCaptureFilterTest {
    @Test
    fun `capture export keeps only structured records inside the selected window`() {
        val history = listOf(
            """{"schemaVersion":2,"recordType":"event","timestampMillis":10}""",
            """{"schemaVersion":2,"recordType":"event","timestampMillis":20}""",
            """{"schemaVersion":2,"recordType":"dropped_events","timestampMillis":25,"count":2}""",
            """{"schemaVersion":2,"recordType":"event","timestampMillis":30}""",
            "not-json",
        ).joinToString("\n")

        val filtered = filterStructuredHistoryForWindow(
            structuredHistory = history,
            window = DiagnosticCaptureWindow(
                id = "capture",
                startedAtMillis = 15,
                endedAtMillis = 25,
            ),
        )

        assertEquals(
            listOf(
                """{"schemaVersion":2,"recordType":"event","timestampMillis":20}""",
                """{"schemaVersion":2,"recordType":"dropped_events","timestampMillis":25,"count":2}""",
            ).joinToString("\n"),
            filtered,
        )
    }
}
