package tachiyomi.core.common.network

import eu.kanade.tachiyomi.network.safeHttpLoggingMessage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SafeHttpLoggingMessageTest {
    @Test
    fun `request and response lines keep useful facts and suppress URLs and every header value`() {
        val messages = listOf(
            "--> GET https://reader:pass@private.example/api/manga?title=secret&token=abc http/1.1",
            "Authorization: Bearer access-token",
            "aUtHoRiZaTiOn: another-access-token",
            "Proxy-Authorization: Basic private-proxy-credential",
            "Cookie: session=private-cookie",
            "Set-Cookie: refresh=private-refresh-token",
            "X-Api-Key: custom-secret",
            "X-Private-Descriptor: private-server-name",
            "Host: private.example",
            "Accept: application/json",
            "<-- 200 OK (888ms) https://private.example/api/manga/(999ms)?title=secret (42ms)",
            "Content-Type: application/json",
            "--> END GET",
            "<-- END HTTP",
        )

        val sanitized = messages.mapNotNull(::safeHttpLoggingMessage)

        sanitized shouldBe listOf(
            "--> GET (URL suppressed)",
            "Authorization: [redacted]",
            "Authorization: [redacted]",
            "Proxy-Authorization: [redacted]",
            "Cookie: [redacted]",
            "Set-Cookie: [redacted]",
            "X-Api-Key: [redacted]",
            "Accept: [redacted]",
            "<-- 200 (42ms)",
            "Content-Type: [redacted]",
            "--> END GET",
            "<-- END HTTP",
        )
        sanitized.joinToString("\n").let { output ->
            output.contains("private.example") shouldBe false
            output.contains("secret") shouldBe false
            output.contains("access-token") shouldBe false
            output.contains("private-proxy-credential") shouldBe false
            output.contains("private-cookie") shouldBe false
            output.contains("private-refresh-token") shouldBe false
            output.contains("custom-secret") shouldBe false
            output.contains("application/json") shouldBe false
            output.contains("X-Private-Descriptor") shouldBe false
            output.contains("private-server-name") shouldBe false
            output.contains("Host") shouldBe false
        }
    }

    @Test
    fun `failed log line suppresses arbitrary exception detail`() {
        safeHttpLoggingMessage(
            "<-- HTTP FAILED: IOException: private server token=secret https://private.example (20ms)",
        ) shouldBe "HTTP FAILED (details suppressed)"
    }
}
