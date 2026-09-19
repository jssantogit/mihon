package tachiyomi.domain.tsuzuki.googleauth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailure
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthState
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthorizationResult
import tachiyomi.domain.tsuzuki.googleauth.service.GoogleAuthStateMachine

class GoogleAuthStateMachineTest {

    private val account = GoogleAccountIdentity("reader@example.com")

    @Test
    fun `case 1 - initial state is signed out`() {
        val machine = GoogleAuthStateMachine()

        machine.state.value shouldBe GoogleAuthState.SignedOut
    }

    @Test
    fun `case 2 - successful connection becomes connected`() {
        val machine = GoogleAuthStateMachine()

        machine.beginConnection()
        machine.state.value shouldBe GoogleAuthState.Connecting

        machine.complete(GoogleAuthorizationResult.Authorized(account))

        machine.state.value shouldBe GoogleAuthState.Connected(account)
    }

    @Test
    fun `case 3 - cancelled connection restores previous signed out state`() {
        val machine = GoogleAuthStateMachine()

        machine.beginConnection()
        machine.complete(GoogleAuthorizationResult.Cancelled)

        machine.state.value shouldBe GoogleAuthState.SignedOut
    }

    @Test
    fun `case 4 - revoked grant becomes authorization required`() {
        val machine = GoogleAuthStateMachine(GoogleAuthState.Connected(account))

        machine.complete(GoogleAuthorizationResult.AuthorizationRequired(account))

        machine.state.value shouldBe GoogleAuthState.AuthorizationRequired(account)
    }

    @Test
    fun `case 5 - cancelled reauthorization restores authorization required state`() {
        val required = GoogleAuthState.AuthorizationRequired(account)
        val machine = GoogleAuthStateMachine(required)

        machine.beginConnection()
        machine.complete(GoogleAuthorizationResult.Cancelled)

        machine.state.value shouldBe required
    }

    @Test
    fun `case 6 - recoverable failure preserves account context`() {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.NETWORK_UNAVAILABLE,
            message = "offline",
        )
        val machine = GoogleAuthStateMachine(GoogleAuthState.Connected(account))

        machine.complete(
            GoogleAuthorizationResult.RecoverableFailure(
                failure = failure,
                account = account,
            ),
        )

        machine.state.value shouldBe GoogleAuthState.RecoverableFailure(
            failure = failure,
            account = account,
        )
    }

    @Test
    fun `case 7 - non recoverable failure becomes error`() {
        val failure = GoogleAuthFailure(
            reason = GoogleAuthFailureReason.CONFIGURATION_ERROR,
            message = "invalid OAuth client",
        )
        val machine = GoogleAuthStateMachine()

        machine.complete(GoogleAuthorizationResult.Failure(failure))

        machine.state.value shouldBe GoogleAuthState.Error(failure)
    }

    @Test
    fun `case 8 - sign out clears any previous connected state`() {
        val machine = GoogleAuthStateMachine(GoogleAuthState.Connected(account))

        machine.signOut()
        machine.beginConnection()
        machine.complete(GoogleAuthorizationResult.Cancelled)

        machine.state.value shouldBe GoogleAuthState.SignedOut
    }

    @Test
    fun `case 9 - restoring is observable and cancellation returns to the stable state`() {
        val machine = GoogleAuthStateMachine(GoogleAuthState.Connected(account))

        machine.beginRestore()
        machine.state.value shouldBe GoogleAuthState.Restoring

        machine.complete(GoogleAuthorizationResult.Cancelled)

        machine.state.value shouldBe GoogleAuthState.Connected(account)
    }

    @Test
    fun `case 10 - blank account identity is rejected`() {
        shouldThrow<IllegalArgumentException> {
            GoogleAccountIdentity("   ")
        }
    }
}
