package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import java.net.ServerSocket

class MihonReadingSourceHttpIntegrationTest {

    @Test
    fun `HTTP 200 valid response passes through Mihon HttpSource parser into gateway`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "/one-punch-man\tOne-Punch Man")

            val candidates = harness.gateway.search(harness.source.id, "One-Punch Man").getOrThrow()

            candidates.single().title shouldBe "One-Punch Man"
            candidates.single().sourceUrl shouldBe "/one-punch-man"
            candidates.single().language shouldBe "en"
        }
    }

    @Test
    fun `HTTP 200 valid empty response remains a successful empty search`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue()

            val result = harness.gateway.search(harness.source.id, "no results")

            result.isSuccess shouldBe true
            result.getOrThrow() shouldBe emptyList()
        }
    }

    @Test
    fun `generic HTTP 403 preserves status and is not classified as CAPTCHA`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(status = 403, body = "private response body")

            val failure = harness.gateway.search(harness.source.id, "One-Punch Man").searchFailure()

            failure.kind shouldBe ReadingSourceFailureKind.HTTP_RESPONSE
            failure.httpStatus shouldBe 403
            failure.message.orEmpty().contains("private response body") shouldBe false
        }
    }

    @Test
    fun `HTTP 429 and 5xx preserve status as structured HTTP failures`() = runTest {
        listOf(429, 503).forEach { status ->
            LocalMihonSourceHarness().use { harness ->
                harness.enqueue(status = status, body = "private response body")

                val failure = harness.gateway.search(harness.source.id, "query").searchFailure()

                failure.kind shouldBe ReadingSourceFailureKind.HTTP_RESPONSE
                failure.httpStatus shouldBe status
                failure.message.orEmpty().contains("private response body") shouldBe false
            }
        }
    }

    @Test
    fun `only an explicit challenge marker is classified as CAPTCHA`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "captcha_required")

            val failure = harness.gateway.search(harness.source.id, "query").searchFailure()

            failure.kind shouldBe ReadingSourceFailureKind.CAPTCHA_REQUIRED
        }
    }

    @Test
    fun `malformed response and extension exception remain distinct`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "malformed")

            val malformed = harness.gateway.search(harness.source.id, "query").searchFailure()
            malformed.kind shouldBe ReadingSourceFailureKind.MALFORMED_RESPONSE

            harness.enqueue(body = "extension_error")
            val extension = harness.gateway.search(harness.source.id, "query").searchFailure()
            extension.kind shouldBe ReadingSourceFailureKind.EXTENSION_FAILURE
        }
    }

    @Test
    fun `generic IO failure remains indeterminate rather than being guessed as network`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "io_error")

            val failure = harness.gateway.search(harness.source.id, "query").searchFailure()

            failure.kind shouldBe ReadingSourceFailureKind.INDETERMINATE
        }
    }

    @Test
    fun `cancellation from extension parser propagates without becoming a search failure`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "cancel_search")

            var observedCancellation = false
            try {
                harness.gateway.search(harness.source.id, "query")
            } catch (_: CancellationException) {
                observedCancellation = true
            }

            observedCancellation shouldBe true
        }
    }

    @Test
    fun `network failure and response timeout have distinct structured categories`() = runTest {
        val closedPortBaseUrl = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }
        LocalMihonSourceHarness(sourceBaseUrl = closedPortBaseUrl).use { harness ->
            val network = harness.gateway.search(harness.source.id, "query").searchFailure()
            network.kind shouldBe ReadingSourceFailureKind.NETWORK_FAILURE
        }

        LocalMihonSourceHarness(readTimeoutMillis = 25).use { harness ->
            harness.enqueueDelayed(body = "", delayMillis = 250)

            val timeout = harness.gateway.search(harness.source.id, "query").searchFailure()
            timeout.kind shouldBe ReadingSourceFailureKind.TIMEOUT
        }
    }

    private fun Result<*>.searchFailure(): ReadingSourceSearchFailure =
        exceptionOrNull() as? ReadingSourceSearchFailure
            ?: error("Expected a structured Mihon reading-source failure")
}
