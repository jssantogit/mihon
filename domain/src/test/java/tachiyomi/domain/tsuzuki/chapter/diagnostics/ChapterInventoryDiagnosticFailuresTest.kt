package tachiyomi.domain.tsuzuki.chapter.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ChapterInventoryDiagnosticFailuresTest {
    @Test
    fun `distinguishes explicit captcha challenge from ordinary IO and timeout`() {
        ChapterInventoryDiagnosticFailures.classify(
            IllegalStateException("wrapper", IOException("Shape-selecting captcha detected")),
        ) shouldBe (ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED to
            ChapterInventoryDiagnosticReason.CAPTCHA_CHALLENGE)
        ChapterInventoryDiagnosticFailures.classify(
            IOException("generic server failure"),
        ) shouldBe (ChapterInventoryDiagnosticOutcome.NETWORK_ERROR to
            ChapterInventoryDiagnosticReason.NETWORK_FAILURE)
        ChapterInventoryDiagnosticFailures.classify(
            IllegalStateException("wrapper", SocketTimeoutException("private network information")),
        ) shouldBe (ChapterInventoryDiagnosticOutcome.TIMEOUT to
            ChapterInventoryDiagnosticReason.TIMEOUT_FAILURE)
    }

    @Test
    fun `non network failures are classified as extension errors`() {
        ChapterInventoryDiagnosticFailures.classify(
            IllegalStateException("unexpected runtime"),
        ) shouldBe (ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR to
            ChapterInventoryDiagnosticReason.EXTENSION_FAILURE)
    }
}
