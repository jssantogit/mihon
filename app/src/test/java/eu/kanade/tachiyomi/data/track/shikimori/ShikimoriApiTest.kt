package eu.kanade.tachiyomi.data.track.shikimori

import io.kotest.matchers.shouldBe
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test

class ShikimoriApiTest {

    @Test
    fun `auth url uses user owned client id and Tsuzuki callback`() {
        val url = ShikimoriApi.authUrl("user-client").toHttpUrl()

        url.queryParameter("client_id") shouldBe "user-client"
        url.queryParameter("redirect_uri") shouldBe ShikimoriApi.CALLBACK_URL
    }

    @Test
    fun `refresh request uses user owned oauth credentials`() {
        val request = ShikimoriApi.refreshTokenRequest(
            token = "refresh",
            clientId = "user-client",
            clientSecret = "user-secret",
        )
        val body = request.body as FormBody

        body.formValue("client_id") shouldBe "user-client"
        body.formValue("client_secret") shouldBe "user-secret"
    }

    private fun FormBody.formValue(name: String): String =
        (0 until size).first { this.name(it) == name }.let { value(it) }
}
