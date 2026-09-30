package eu.kanade.tachiyomi.data.track.myanimelist

import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.jupiter.api.Test

class MyAnimeListApiTest {

    @Test
    fun `auth url uses the user supplied client id`() {
        val url = MyAnimeListApi.authUrl(
            clientId = "user-owned-client",
            codeVerifier = "verifier-value",
            state = "state-value",
        ).toHttpUrl()

        url.queryParameter("client_id") shouldBe "user-owned-client"
    }

    @Test
    fun `authorization request binds callback state and persisted pkce verifier`() {
        val url = MyAnimeListApi.authUrl(
            clientId = "user-owned-client",
            codeVerifier = "verifier-value",
            state = "state-value",
        ).toHttpUrl()

        url.queryParameter("redirect_uri") shouldBe MyAnimeListApi.CALLBACK_URL
        url.queryParameter("state") shouldBe "state-value"
        url.queryParameter("code_challenge") shouldBe "verifier-value"
        url.queryParameter("code_challenge_method") shouldBe "plain"
    }

    @Test
    fun `access token request reuses callback and persisted pkce verifier`() {
        val request = MyAnimeListApi.accessTokenRequest(
            authCode = "authorization-code",
            clientId = "user-owned-client",
            codeVerifier = "verifier-value",
        )
        val body = request.body as FormBody

        body.formValue("client_id") shouldBe "user-owned-client"
        body.formValue("code") shouldBe "authorization-code"
        body.formValue("code_verifier") shouldBe "verifier-value"
        body.formValue("redirect_uri") shouldBe MyAnimeListApi.CALLBACK_URL
        body.formValue("grant_type") shouldBe "authorization_code"
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

        val clientId = (0 until body.size)
            .first { body.name(it) == "client_id" }
            .let(body::value)

        clientId shouldBe "user-owned-client"
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

    private fun FormBody.formValue(name: String): String =
        (0 until size).first { this.name(it) == name }.let { value(it) }
}
