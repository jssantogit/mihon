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
            codeVerifier = "persisted-verifier",
        ).toHttpUrl()

        url.queryParameter("client_id") shouldBe "user-owned-client"
        url.queryParameter("redirect_uri") shouldBe MyAnimeListApi.CALLBACK_URL
        url.queryParameter("code_challenge") shouldBe "persisted-verifier"
        url.queryParameter("code_challenge_method") shouldBe "plain"
    }

    @Test
    fun `user manga list explicitly requests list status for library import`() {
        val url = MyAnimeListApi.userMangaListUrl(offset = 250).toHttpUrl()

        url.queryParameter("fields")
            ?.split(",")
            ?.contains("list_status") shouldBe true
        url.queryParameter("limit") shouldBe "250"
        url.queryParameter("offset") shouldBe "250"
    }

    @Test
    fun `access token request reuses redirect uri and persisted verifier`() {
        val request = MyAnimeListApi.accessTokenRequest(
            authCode = "authorization-code",
            codeVerifier = "persisted-verifier",
            clientId = "user-owned-client",
        )
        val body = request.body as FormBody
        val values = (0 until body.size).associate { index ->
            body.name(index) to body.value(index)
        }

        values["client_id"] shouldBe "user-owned-client"
        values["client_secret"] shouldBe null
        values["code"] shouldBe "authorization-code"
        values["code_verifier"] shouldBe "persisted-verifier"
        values["redirect_uri"] shouldBe MyAnimeListApi.CALLBACK_URL
        values["grant_type"] shouldBe "authorization_code"
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

        val values = (0 until body.size).associate { index ->
            body.name(index) to body.value(index)
        }

        values["client_id"] shouldBe "user-owned-client"
        values["client_secret"] shouldBe null
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
