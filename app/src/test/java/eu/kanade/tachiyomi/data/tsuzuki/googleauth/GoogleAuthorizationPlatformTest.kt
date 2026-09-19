package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailure
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason

class GoogleAuthorizationPlatformTest {

    private val account = GoogleAccountIdentity("reader@example.com")

    @Test
    fun `case 1 - fake can return an authorized transient session`() = runTest {
        val session = GoogleAuthorizationSession(account, "secret-access-token")
        val fake = FakeGoogleAuthorizationPlatform(
            authorizationResults = listOf(GoogleAuthorizationPlatformResult.Authorized(session)),
        )

        fake.authorize() shouldBe GoogleAuthorizationPlatformResult.Authorized(session)
        fake.authorizeHints shouldContainExactly listOf(null)
    }

    @Test
    fun `case 2 - fake can require an interactive user action`() = runTest {
        val action = FakeUserAction("consent")
        val expected = GoogleAuthorizationPlatformResult.UserActionRequired(
            action = action,
            account = account,
        )
        val fake = FakeGoogleAuthorizationPlatform(authorizationResults = listOf(expected))

        fake.authorize(account) shouldBe expected
        fake.authorizeHints shouldContainExactly listOf(account)
    }

    @Test
    fun `case 3 - fake can simulate user cancellation`() = runTest {
        val fake = FakeGoogleAuthorizationPlatform(
            authorizationResults = listOf(GoogleAuthorizationPlatformResult.Cancelled),
        )

        fake.authorize() shouldBe GoogleAuthorizationPlatformResult.Cancelled
    }

    @Test
    fun `case 4 - fake can simulate a recoverable authorization failure`() = runTest {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.NETWORK_UNAVAILABLE,
            message = "offline",
        )
        val expected = GoogleAuthorizationPlatformResult.RecoverableFailure(
            failure = failure,
            account = account,
        )
        val fake = FakeGoogleAuthorizationPlatform(authorizationResults = listOf(expected))

        fake.authorize(account) shouldBe expected
    }

    @Test
    fun `case 5 - fake can simulate a fatal authorization failure`() = runTest {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.CONFIGURATION_ERROR,
            message = "bad OAuth configuration",
        )
        val expected = GoogleAuthorizationPlatformResult.Failure(failure)
        val fake = FakeGoogleAuthorizationPlatform(authorizationResults = listOf(expected))

        fake.authorize() shouldBe expected
    }

    @Test
    fun `case 6 - session string representation never exposes the access token`() {
        val token = "secret-access-token"
        val session = GoogleAuthorizationSession(account, token)

        session.toString() shouldNotContain token
        session.toString() shouldBe "GoogleAuthorizationSession(account=$account, accessToken=[REDACTED])"
    }

    @Test
    fun `case 7 - blank access tokens are rejected`() {
        shouldThrow<IllegalArgumentException> {
            GoogleAuthorizationSession(account, "   ")
        }
    }

    @Test
    fun `case 8 - fake records revoke and token-clear operations without exposing token values`() = runTest {
        val session = GoogleAuthorizationSession(account, "secret-access-token")
        val fake = FakeGoogleAuthorizationPlatform()

        fake.revoke(account) shouldBe GoogleAuthorizationOperationResult.Success
        fake.clearAccessToken(session) shouldBe GoogleAuthorizationOperationResult.Success

        fake.revokedAccounts shouldContainExactly listOf(account)
        fake.clearedSessions shouldContainExactly listOf(session)
    }

    private data class FakeUserAction(
        val id: String,
    ) : GoogleAuthorizationUserAction

    private class FakeGoogleAuthorizationPlatform(
        authorizationResults: List<GoogleAuthorizationPlatformResult> = emptyList(),
        private val revokeResult: GoogleAuthorizationOperationResult = GoogleAuthorizationOperationResult.Success,
        private val clearTokenResult: GoogleAuthorizationOperationResult = GoogleAuthorizationOperationResult.Success,
    ) : GoogleAuthorizationPlatform {

        private val pendingAuthorizationResults = ArrayDeque(authorizationResults)

        val authorizeHints = mutableListOf<GoogleAccountIdentity?>()
        val revokedAccounts = mutableListOf<GoogleAccountIdentity>()
        val clearedSessions = mutableListOf<GoogleAuthorizationSession>()

        override suspend fun authorize(accountHint: GoogleAccountIdentity?): GoogleAuthorizationPlatformResult {
            authorizeHints += accountHint
            check(pendingAuthorizationResults.isNotEmpty()) { "No fake authorization result queued" }
            return pendingAuthorizationResults.removeFirst()
        }

        override suspend fun revoke(account: GoogleAccountIdentity): GoogleAuthorizationOperationResult {
            revokedAccounts += account
            return revokeResult
        }

        override suspend fun clearAccessToken(session: GoogleAuthorizationSession): GoogleAuthorizationOperationResult {
            clearedSessions += session
            return clearTokenResult
        }
    }
}
