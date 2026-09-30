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
                "source_id" to DiagnosticAttributeValue.Number(-42),
                "preferred_source_count" to DiagnosticAttributeValue.Number(2),
                "target_source_count" to DiagnosticAttributeValue.Number(1),
                "http_status" to DiagnosticAttributeValue.Number(503),
                "language" to DiagnosticAttributeValue.Text("en-US"),
                "canonical_title_ref" to DiagnosticAttributeValue.Text("0123456789abcdef"),
                "mihon_manga_ref" to DiagnosticAttributeValue.Text("fedcba9876543210"),
                "search_text" to DiagnosticAttributeValue.Text("secret manga title"),
                "api_key" to DiagnosticAttributeValue.Text("sk_test_secret"),
                "extra" to DiagnosticAttributeValue.Text("https://user:pass@example.test/path?token=secret"),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.attributes shouldBe mapOf(
            DiagnosticAttribute.SOURCE_ID to DiagnosticAttributeValue.Number(-42),
            DiagnosticAttribute.PREFERRED_SOURCE_COUNT to DiagnosticAttributeValue.Number(2),
            DiagnosticAttribute.TARGET_SOURCE_COUNT to DiagnosticAttributeValue.Number(1),
            DiagnosticAttribute.HTTP_STATUS to DiagnosticAttributeValue.Number(503),
            DiagnosticAttribute.LANGUAGE to DiagnosticAttributeValue.Text("en-US"),
            DiagnosticAttribute.CANONICAL_TITLE_REF to DiagnosticAttributeValue.Text("0123456789abcdef"),
            DiagnosticAttribute.MIHON_MANGA_REF to DiagnosticAttributeValue.Text("fedcba9876543210"),
        )
        sanitized?.attributes.toString().contains("secret") shouldBe false
        sanitized?.attributes.toString().contains("example.test") shouldBe false
        sanitized?.attributes.toString().contains("search_text") shouldBe false
    }

    @Test
    fun `sanitizer rejects secret shaped correlation ids`() {
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

    @Test
    fun `sanitizer drops secret url token and cookie values under a recognized attribute key`() {
        listOf(
            "https://user:pass@private.example/path?token=secret",
            "Bearer top-secret-token",
            "session=private-cookie",
        ).forEach { unsafeLanguage ->
            val event = StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000,
                severity = DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.SOURCE,
                name = DiagnosticEventName.SOURCE_RESOLVE_STARTED,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.RESOLVE,
                outcome = DiagnosticOutcome.STARTED,
                attributes = mapOf(
                    "language" to DiagnosticAttributeValue.Text(unsafeLanguage),
                ),
            )

            StructuredDiagnosticSanitizer.sanitize(event)?.attributes shouldBe emptyMap()
        }
    }
}
