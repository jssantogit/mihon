package tachiyomi.core.common.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SafeHttpLoggingMessageTest {
    @Test
    fun `request and response lines keep useful facts and suppress URLs and every header value`() {
        val messages = listOf(
            "--> GET https://reader:pass@private.example/api/manga?title=secret&token=abc http/1.1",
            "Authorization: Bearer access-token",
            "Cookie: session=private-cookie",
            "X-Api-Key: custom-secret",
            "Accept: application/json",
            "<-- 200 OK https://private.example/api/manga?title=secret (42ms, 128-byte body)",
            "Content-Type: application/json",
            "--> END GET (0-byte body)",
            "<-- END HTTP (128-byte body)",
        )

        val sanitized = messages.mapNotNull(::safeHttpLoggingMessage)

        sanitized shouldBe listOf(
            "--> GET (URL suppressed)",
            "Authorization: [redacted]",
            "Cookie: [redacted]",
            "X-Api-Key: [redacted]",
            "Accept: [redacted]",
            "<-- 200 (42ms)",
            "Content-Type: [redacted]",
            "--> END GET (0-byte body)",
            "<-- END HTTP",
        )
        sanitized.joinToString("\n").let { output ->
            output.contains("private.example") shouldBe false
            output.contains("secret") shouldBe false
            output.contains("access-token") shouldBe false
            output.contains("private-cookie") shouldBe false
            output.contains("custom-secret") shouldBe false
            output.contains("application/json") shouldBe false
        }
    }

    @Test
    fun `failed request line suppresses arbitrary exception detail`() {
        safeHttpLoggingMessage("--> HTTP FAILED: IOException: private server token=secret") shouldBe
            "HTTP FAILED (details suppressed)"
    }
}
