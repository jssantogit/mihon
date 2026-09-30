package eu.kanade.tachiyomi.data.track

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UserOwnedCredentialSessionTest {

    @Test
    fun `legacy oauth session is inactive without user owned application credentials`() {
        userOwnedCredentialSessionActive(
            baseLoggedIn = true,
            "",
        ) shouldBe false
    }

    @Test
    fun `session is active when base login and all user owned credentials are present`() {
        userOwnedCredentialSessionActive(
            baseLoggedIn = true,
            "client-id",
            "client-secret",
        ) shouldBe true
    }

    @Test
    fun `bangumi legacy oauth password cannot impersonate personal token login`() {
        personalTokenSessionActive(
            baseLoggedIn = true,
            password = "legacy-oauth-token",
            accessToken = "personal-token",
            expectedPasswordMarker = "personal_access_token",
        ) shouldBe false
    }
}
