package eu.kanade.tachiyomi.data.track.bangumi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.Request
import org.junit.jupiter.api.Test

class BangumiApiTest {

    @Test
    fun `personal access token authorizes Bangumi api requests`() {
        val request = Request.Builder()
            .url("https://api.bgm.tv/v0/me")
            .build()

        val authorized = BangumiApi.authorizeRequest(request, "personal-token")

        authorized.header("Authorization") shouldBe "Bearer personal-token"
    }

    @Test
    fun `blank personal token is rejected`() {
        val request = Request.Builder()
            .url("https://api.bgm.tv/v0/me")
            .build()

        shouldThrow<BangumiAccessTokenMissing> {
            BangumiApi.authorizeRequest(request, " ")
        }
    }
}
