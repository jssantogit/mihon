package eu.kanade.tachiyomi.data.track.myanimelist

import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.FormBody
import okhttp3.Request
import org.junit.jupiter.api.Test

class MyAnimeListApiTest {

    @Test
    fun `auth url uses the user supplied client id`() {
        val uri = MyAnimeListApi.authUrl("user-owned-client")

        uri.getQueryParameter("client_id") shouldBe "user-owned-client"
    }

    @Test
    fun `refresh token request uses the user supplied client id`() {
        val oauth = MALOAuth(
            refreshToken = "refresh",
            accessToken = "access",
            expiresIn = 3600,
            createdAt = 1,
        )

        val request = MyAnimeListApi.refreshTokenRequest(oauth, "user-owned-client")
        val body = request.body as FormBody

        body.value(body.names.indexOf("client_id")) shouldBe "user-owned-client"
    }

    @Test
    fun `public api requests use client id without requiring oauth`() {
        val original = Request.Builder()
            .url("https://api.myanimelist.net/v2/manga/1")
            .build()

        val request = MyAnimeListApi.authorizeRequest(
            request = original,
            clientId = "user-owned-client",
            accessToken = null,
        )

        request.header("X-MAL-CLIENT-ID") shouldBe "user-owned-client"
        request.header("Authorization") shouldBe null
    }

    @Test
    fun `authenticated api requests include both client id and bearer token`() {
        val original = Request.Builder()
            .url("https://api.myanimelist.net/v2/users/@me")
            .build()

        val request = MyAnimeListApi.authorizeRequest(
            request = original,
            clientId = "user-owned-client",
            accessToken = "access-token",
        )

        request.header("X-MAL-CLIENT-ID") shouldBe "user-owned-client"
        request.header("Authorization") shouldBe "Bearer access-token"
    }

    @Test
    fun `blank client id is rejected before any MAL request is sent`() {
        val original = Request.Builder()
            .url("https://api.myanimelist.net/v2/manga/1")
            .build()

        shouldThrow<MALClientIdMissing> {
            MyAnimeListApi.authorizeRequest(
                request = original,
                clientId = "   ",
                accessToken = null,
            )
        }
    }
}
