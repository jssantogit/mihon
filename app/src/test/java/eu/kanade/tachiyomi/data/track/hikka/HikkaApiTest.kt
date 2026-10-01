package eu.kanade.tachiyomi.data.track.hikka

import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
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
    fun `catalog mapping uses hikka native score instead of imported aggregate`() {
        val manga = Json { ignoreUnknownKeys = true }.decodeFromString<HKManga>(
            """
            {
              "data_type": "manga",
              "title_original": "Example",
              "media_type": "manga",
              "translated_ua": false,
              "status": "ongoing",
              "image": "https://example.invalid/cover.jpg",
              "native_scored_by": 42,
              "native_score": 7.2,
              "scored_by": 836,
              "score": 8.5,
              "slug": "example-123"
            }
            """.trimIndent(),
        )

        val track = manga.toTrack(10L)

        track.score shouldBe 7.2
        track.score_votes shouldBe 42
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
