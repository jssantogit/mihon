package tachiyomi.domain.tsuzuki.googleauth.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthState
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthorizationResult

class GoogleAuthStateMachine(
    initialState: GoogleAuthState = GoogleAuthState.SignedOut,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<GoogleAuthState> = mutableState.asStateFlow()

    private var previousStableState: GoogleAuthState = initialState.stableFallback()

    fun beginRestore() {
        previousStableState = currentStableState()
        mutableState.value = GoogleAuthState.Restoring
    }

    fun beginConnection() {
        previousStableState = currentStableState()
        mutableState.value = GoogleAuthState.Connecting
    }

    fun complete(result: GoogleAuthorizationResult) {
        when (result) {
            is GoogleAuthorizationResult.Authorized -> setStable(
                GoogleAuthState.Connected(result.account),
            )

            is GoogleAuthorizationResult.AuthorizationRequired -> setStable(
                GoogleAuthState.AuthorizationRequired(result.account),
            )

            is GoogleAuthorizationResult.RecoverableFailure -> setStable(
                GoogleAuthState.RecoverableFailure(
                    failure = result.failure,
                    account = result.account,
                ),
            )

            is GoogleAuthorizationResult.Failure -> setStable(
                GoogleAuthState.Error(result.failure),
            )

            GoogleAuthorizationResult.Cancelled -> mutableState.value = previousStableState
        }
    }

    fun signOut() {
        setStable(GoogleAuthState.SignedOut)
    }

    private fun currentStableState(): GoogleAuthState {
        return when (mutableState.value) {
            GoogleAuthState.Restoring,
            GoogleAuthState.Connecting,
            -> previousStableState

            else -> mutableState.value
        }
    }

    private fun setStable(state: GoogleAuthState) {
        mutableState.value = state
        previousStableState = state
    }

    private fun GoogleAuthState.stableFallback(): GoogleAuthState {
        return when (this) {
            GoogleAuthState.Restoring,
            GoogleAuthState.Connecting,
            -> GoogleAuthState.SignedOut

            else -> this
        }
    }
}
