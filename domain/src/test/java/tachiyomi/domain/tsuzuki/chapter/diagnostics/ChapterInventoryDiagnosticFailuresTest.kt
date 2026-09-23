package tachiyomi.domain.tsuzuki.chapter.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class ChapterInventoryDiagnosticFailuresTest {
    @Test
    fun `distinguishes explicit captcha challenge from ordinary IO and timeout`() {
        ChapterInventoryDiagnosticFailures.classify(
            IllegalStateException("wrapper", IOException("Shape-selecting captcha detected")),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED to
                ChapterInventoryDiagnosticReason.CAPTCHA_CHALLENGE
            )
        ChapterInventoryDiagnosticFailures.classify(
            IOException("generic server failure"),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.INDETERMINATE to
                ChapterInventoryDiagnosticReason.INDETERMINATE_FAILURE
            )
        ChapterInventoryDiagnosticFailures.classify(
            ConnectException("connection refused"),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.NETWORK_ERROR to
                ChapterInventoryDiagnosticReason.NETWORK_FAILURE
            )
        ChapterInventoryDiagnosticFailures.classify(
            IllegalStateException("wrapper", SocketTimeoutException("private network information")),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.TIMEOUT to
                ChapterInventoryDiagnosticReason.TIMEOUT_FAILURE
            )
    }

    @Test
    fun `non network failures are classified as extension errors`() {
        ChapterInventoryDiagnosticFailures.classify(
            IllegalStateException("unexpected runtime"),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR to
                ChapterInventoryDiagnosticReason.EXTENSION_FAILURE
        )
    }

    @Test
    fun `structured source search failures keep HTTP CAPTCHA and unknown distinct`() {
        ChapterInventoryDiagnosticFailures.classify(
            ReadingSourceSearchFailure(
                kind = ReadingSourceFailureKind.HTTP_RESPONSE,
                httpStatus = 403,
                cause = IllegalStateException("private response details"),
            ),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.HTTP_ERROR to
                ChapterInventoryDiagnosticReason.HTTP_FORBIDDEN
            )

        ChapterInventoryDiagnosticFailures.classify(
            ReadingSourceSearchFailure(
                kind = ReadingSourceFailureKind.CAPTCHA_REQUIRED,
                cause = IllegalStateException("captcha_required"),
            ),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED to
                ChapterInventoryDiagnosticReason.CAPTCHA_CHALLENGE
            )

        ChapterInventoryDiagnosticFailures.classify(
            ReadingSourceSearchFailure(
                kind = ReadingSourceFailureKind.INDETERMINATE,
                cause = IllegalArgumentException("private parser failure"),
            ),
        ) shouldBe (
            ChapterInventoryDiagnosticOutcome.INDETERMINATE to
                ChapterInventoryDiagnosticReason.INDETERMINATE_FAILURE
            )
    }
}
