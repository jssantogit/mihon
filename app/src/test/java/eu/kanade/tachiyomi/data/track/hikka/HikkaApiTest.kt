package eu.kanade.tachiyomi.data.track.hikka

import io.kotest.matchers.shouldBe
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.jupiter.api.Test

class HikkaApiTest {

    @Test
    fun `auth url uses user owned application reference`() {
        val url = HikkaApi.authUrl("user-reference").toHttpUrl()

        url.queryParameter("reference") shouldBe "user-reference"
    }

    @Test
    fun `token exchange uses user owned application secret`() {
        val request = HikkaApi.authTokenCreate(
            requestReference = "request-reference",
            clientSecret = "user-secret",
        )
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val payload = buffer.readUtf8()

        payload.contains("\"request_reference\":\"request-reference\"") shouldBe true
        payload.contains("\"client_secret\":\"user-secret\"") shouldBe true
    }
}
