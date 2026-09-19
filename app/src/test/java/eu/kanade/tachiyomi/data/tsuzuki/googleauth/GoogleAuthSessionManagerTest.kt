package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailure
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthState

class GoogleAuthSessionManagerTest {

    private val account = GoogleAccountIdentity("reader@example.com")

    @Test
    fun `case 1 - manager starts in restoring state`() {
        val manager = manager()

        manager.state.value shouldBe GoogleAuthState.Restoring
    }

    @Test
    fun `case 2 - missing hint restores signed out without calling Google`() = runTest {
        val platform = FakeAuthorizationPlatform()
        val manager = manager(
            platform = platform,
            hintStore = FakeAccountHintStore(),
        )

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.SignedOut
        platform.authorizeHints shouldBe emptyList()
        manager.currentSession().shouldBeNull()
    }

    @Test
    fun `case 3 - persisted hint is revalidated before connected is restored`() = runTest {
        val session = GoogleAuthorizationSession(account, "transient-token")
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Authorized(session),
            ),
        )
        val hintStore = FakeAccountHintStore(account)
        val manager = manager(platform, hintStore)

        manager.restore()

        platform.authorizeHints shouldContainExactly listOf(account)
        manager.state.value shouldBe GoogleAuthState.Connected(account)
        manager.currentSession() shouldBe session
        hintStore.writtenAccounts shouldContainExactly listOf(account)
    }

    @Test
    fun `case 4 - interactive grant requirement restores authorization required`() = runTest {
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = FakeUserAction,
                    account = account,
                ),
            ),
        )
        val manager = manager(platform, FakeAccountHintStore(account))

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.AuthorizationRequired(account)
        manager.currentSession().shouldBeNull()
    }

    @Test
    fun `case 5 - temporary network failure remains recoverable`() = runTest {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.NETWORK_UNAVAILABLE,
            message = "offline",
        )
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.RecoverableFailure(
                    failure = failure,
                    account = account,
                ),
            ),
        )
        val manager = manager(platform, FakeAccountHintStore(account))

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.RecoverableFailure(
            failure = failure,
            account = account,
        )
        manager.currentSession().shouldBeNull()
    }

    @Test
    fun `case 6 - unavailable account requires reauthorization instead of trusting hint`() = runTest {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE,
            message = "account unavailable",
        )
        val hintStore = FakeAccountHintStore(account)
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.RecoverableFailure(
                    failure = failure,
                    account = account,
                ),
            ),
        )
        val manager = manager(platform, hintStore)

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.AuthorizationRequired(account)
        manager.currentSession().shouldBeNull()
        hintStore.read().shouldBeNull()
    }

    @Test
    fun `case 7 - fatal configuration failure restores error without a session`() = runTest {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.CONFIGURATION_ERROR,
            message = "bad OAuth configuration",
        )
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Failure(failure),
            ),
        )
        val manager = manager(platform, FakeAccountHintStore(account))

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.Error(failure)
        manager.currentSession().shouldBeNull()
    }

    @Test
    fun `case 8 - cancelled restoration requires explicit authorization`() = runTest {
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Cancelled,
            ),
        )
        val manager = manager(platform, FakeAccountHintStore(account))

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.AuthorizationRequired(account)
        manager.currentSession().shouldBeNull()
    }

    @Test
    fun `case 9 - hint persistence failure does not invalidate a live authorized session`() = runTest {
        val session = GoogleAuthorizationSession(account, "transient-token")
        val hintStore = FakeAccountHintStore(account, failWrites = true)
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Authorized(session),
            ),
        )
        val manager = manager(platform, hintStore)

        manager.restore()

        manager.state.value shouldBe GoogleAuthState.Connected(account)
        manager.currentSession() shouldBe session
    }

    @Test
    fun `case 10 - explicit connect can complete without user interaction`() = runTest {
        val session = GoogleAuthorizationSession(account, "transient-token")
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Authorized(session),
            ),
        )
        val hintStore = FakeAccountHintStore()
        val manager = manager(platform, hintStore)

        manager.connect() shouldBe GoogleAuthConnectResult.Completed

        manager.state.value shouldBe GoogleAuthState.Connected(account)
        manager.currentSession() shouldBe session
        hintStore.read() shouldBe account
    }

    @Test
    fun `case 11 - explicit connect exposes only opaque user action and stays connecting`() = runTest {
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = FakeUserAction,
                    account = account,
                ),
            ),
        )
        val manager = manager(platform, FakeAccountHintStore(account))

        manager.connect() shouldBe GoogleAuthConnectResult.UserActionRequired(FakeUserAction)

        manager.state.value shouldBe GoogleAuthState.Connecting
        manager.pendingInteractiveAccountHint() shouldBe account
        manager.currentSession().shouldBeNull()
    }

    @Test
    fun `case 12 - cancelling first interactive connection restores signed out`() = runTest {
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = FakeUserAction,
                ),
            ),
        )
        val manager = manager(platform, FakeAccountHintStore())

        manager.connect()
        manager.completeInteractive(GoogleAuthorizationPlatformResult.Cancelled) shouldBe
            GoogleAuthConnectResult.Completed

        manager.state.value shouldBe GoogleAuthState.SignedOut
        manager.pendingInteractiveAccountHint().shouldBeNull()
    }

    @Test
    fun `case 13 - interactive success stores only the resulting session and account hint`() = runTest {
        val session = GoogleAuthorizationSession(account, "transient-token")
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = FakeUserAction,
                ),
            ),
        )
        val hintStore = FakeAccountHintStore()
        val manager = manager(platform, hintStore)

        manager.connect()
        manager.completeInteractive(
            GoogleAuthorizationPlatformResult.Authorized(session),
        ) shouldBe GoogleAuthConnectResult.Completed

        manager.state.value shouldBe GoogleAuthState.Connected(account)
        manager.currentSession() shouldBe session
        hintStore.read() shouldBe account
    }

    @Test
    fun `case 14 - cancelled reauthorization restores authorization required state`() = runTest {
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = FakeUserAction,
                    account = account,
                ),
                GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = FakeUserAction,
                    account = account,
                ),
            ),
        )
        val manager = manager(platform, FakeAccountHintStore(account))

        manager.restore()
        manager.connect()
        manager.completeInteractive(GoogleAuthorizationPlatformResult.Cancelled)

        manager.state.value shouldBe GoogleAuthState.AuthorizationRequired(account)
    }

    @Test
    fun `case 15 - disconnect clears local auth before clearing token and revoking grant`() = runTest {
        val session = GoogleAuthorizationSession(account, "transient-token")
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Authorized(session),
            ),
        )
        val hintStore = FakeAccountHintStore(account)
        val manager = manager(platform, hintStore)

        manager.restore()
        manager.disconnect() shouldBe GoogleAuthorizationOperationResult.Success

        manager.state.value shouldBe GoogleAuthState.SignedOut
        manager.currentSession().shouldBeNull()
        hintStore.read().shouldBeNull()
        platform.clearedSessions shouldContainExactly listOf(session)
        platform.revokedAccounts shouldContainExactly listOf(account)
    }

    @Test
    fun `case 16 - revoke failure still leaves Tsuzuki locally signed out`() = runTest {
        val session = GoogleAuthorizationSession(account, "transient-token")
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.NETWORK_UNAVAILABLE,
            message = "offline",
        )
        val revokeResult = GoogleAuthorizationOperationResult.RecoverableFailure(failure)
        val platform = FakeAuthorizationPlatform(
            authorizationResults = listOf(
                GoogleAuthorizationPlatformResult.Authorized(session),
            ),
            revokeResult = revokeResult,
        )
        val hintStore = FakeAccountHintStore(account)
        val manager = manager(platform, hintStore)

        manager.restore()
        manager.disconnect() shouldBe revokeResult

        manager.state.value shouldBe GoogleAuthState.SignedOut
        manager.currentSession().shouldBeNull()
        hintStore.read().shouldBeNull()
    }

    private fun manager(
        platform: FakeAuthorizationPlatform = FakeAuthorizationPlatform(),
        hintStore: FakeAccountHintStore = FakeAccountHintStore(),
    ) = GoogleAuthSessionManager(
        authorizationPlatform = platform,
        accountHintStore = hintStore,
    )

    private data object FakeUserAction : GoogleAuthorizationUserAction

    private class FakeAccountHintStore(
        private var account: GoogleAccountIdentity? = null,
        private val failWrites: Boolean = false,
    ) : GoogleAccountHintStore {

        val writtenAccounts = mutableListOf<GoogleAccountIdentity>()

        override fun read(): GoogleAccountIdentity? = account

        override fun write(account: GoogleAccountIdentity) {
            if (failWrites) {
                error("simulated hint persistence failure")
            }
            this.account = account
            writtenAccounts += account
        }

        override fun clear() {
            account = null
        }
    }

    private class FakeAuthorizationPlatform(
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
