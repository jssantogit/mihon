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
    fun `sanitizer keeps bounded Tsuzuki Rating composition without arbitrary payloads`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.METADATA,
            name = DiagnosticEventName.TSUZUKI_RATING_COMPUTED,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            stage = DiagnosticStage.SUMMARY,
            outcome = DiagnosticOutcome.SUCCEEDED,
            attributes = mapOf(
                "rating_source_count" to DiagnosticAttributeValue.Number(6),
                "rating_verified_source_count" to DiagnosticAttributeValue.Number(4),
                "rating_corroborated_source_count" to DiagnosticAttributeValue.Number(2),
                "tsuzuki_rating_enabled" to DiagnosticAttributeValue.Flag(true),
                "tsuzuki_rating_present" to DiagnosticAttributeValue.Flag(true),
                "raw_rating_payload" to DiagnosticAttributeValue.Text("secret-title:9.8"),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.attributes shouldBe mapOf(
            DiagnosticAttribute.RATING_SOURCE_COUNT to DiagnosticAttributeValue.Number(6),
            DiagnosticAttribute.RATING_VERIFIED_SOURCE_COUNT to DiagnosticAttributeValue.Number(4),
            DiagnosticAttribute.RATING_CORROBORATED_SOURCE_COUNT to DiagnosticAttributeValue.Number(2),
            DiagnosticAttribute.TSUZUKI_RATING_ENABLED to DiagnosticAttributeValue.Flag(true),
            DiagnosticAttribute.TSUZUKI_RATING_PRESENT to DiagnosticAttributeValue.Flag(true),
        )
        sanitized?.attributes.toString().contains("secret-title") shouldBe false
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

    @Test
    fun `sanitizer preserves safe workflow correlation and rejects unsafe provider ids`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.ARTWORK,
            name = DiagnosticEventName.ARTWORK_RESOLVE_STARTED,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            workflowId = "e1bf2346-14d8-4ef6-9f16-c03551f16013",
            parentOperationId = "6a024bea-3d20-4035-9a4f-969237ee67da",
            workflow = DiagnosticWorkflow.ARTWORK_RESOLUTION,
            stage = DiagnosticStage.RESOLVE,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                "provider_id" to DiagnosticAttributeValue.Text("kitsu"),
                "request_data_present" to DiagnosticAttributeValue.Flag(true),
                "invariant_code" to DiagnosticAttributeValue.Code(
                    DiagnosticInvariantCode.ARTWORK_LOST_AFTER_RESOLUTION,
                ),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.workflowId shouldBe "e1bf2346-14d8-4ef6-9f16-c03551f16013"
        sanitized?.parentOperationId shouldBe "6a024bea-3d20-4035-9a4f-969237ee67da"
        sanitized?.workflow shouldBe DiagnosticWorkflow.ARTWORK_RESOLUTION
        sanitized?.attributes?.get(DiagnosticAttribute.PROVIDER_ID) shouldBe DiagnosticAttributeValue.Text("kitsu")
    }
}
