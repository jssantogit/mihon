package tachiyomi.domain.tsuzuki.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StructuredDiagnosticEventTest {
    @Test
    fun `sanitizer keeps typed safe fields and drops unknown keys and unsafe values`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.WARN,
            subsystem = DiagnosticSubsystem.SOURCE,
            name = DiagnosticEventName.SOURCE_SEARCH_COMPLETED,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            stage = DiagnosticStage.SEARCH,
            outcome = DiagnosticOutcome.FAILED,
            attributes = mapOf(
                "source_id" to DiagnosticAttributeValue.Number(42),
                "language" to DiagnosticAttributeValue.Text("en-US"),
                "search_text" to DiagnosticAttributeValue.Text("secret manga title"),
                "api_key" to DiagnosticAttributeValue.Text("sk_test_secret"),
                "extra" to DiagnosticAttributeValue.Text("https://user:pass@example.test/path?token=secret"),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.attributes shouldBe mapOf(
            DiagnosticAttribute.SOURCE_ID to DiagnosticAttributeValue.Number(42),
            DiagnosticAttribute.LANGUAGE to DiagnosticAttributeValue.Text("en-US"),
        )
        sanitized?.toString()?.contains("secret") shouldBe false
        sanitized?.toString()?.contains("example.test") shouldBe false
        sanitized?.toString()?.contains("search_text") shouldBe false
    }

    @Test
    fun `sanitizer rejects secret shaped correlation ids and malformed language values`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.SOURCE,
            name = DiagnosticEventName.SOURCE_RESOLVE_STARTED,
            sessionId = "https://private.example/?token=secret",
            operationId = null,
            stage = DiagnosticStage.RESOLVE,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                "language" to DiagnosticAttributeValue.Text("Bearer top-secret-token"),
            ),
        )

        StructuredDiagnosticSanitizer.sanitize(event) shouldBe null
    }
}
