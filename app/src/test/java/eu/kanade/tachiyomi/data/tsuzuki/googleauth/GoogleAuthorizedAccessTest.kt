package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class GoogleAuthorizedAccessTest {

    @Test
    fun `case 1 - token wrapper never exposes raw value in string representation`() {
        val rawToken = "super-secret-token"
        val token = GoogleAccessToken(rawToken)

        token.toString() shouldBe "GoogleAccessToken([REDACTED])"
        token.toString() shouldNotContain rawToken
    }

    @Test
    fun `case 2 - blank token wrappers are rejected`() {
        shouldThrow<IllegalArgumentException> {
            GoogleAccessToken("   ")
        }
    }
}
