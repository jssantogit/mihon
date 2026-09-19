package tachiyomi.domain.tsuzuki.googleauth.model

sealed interface GoogleAuthState {
    data object Restoring : GoogleAuthState

    data object SignedOut : GoogleAuthState

    data object Connecting : GoogleAuthState

    data class Connected(
        val account: GoogleAccountIdentity,
    ) : GoogleAuthState

    data class AuthorizationRequired(
        val account: GoogleAccountIdentity? = null,
    ) : GoogleAuthState

    data class RecoverableFailure(
        val failure: GoogleAuthFailure,
        val account: GoogleAccountIdentity? = null,
    ) : GoogleAuthState

    data class Error(
        val failure: GoogleAuthFailure,
    ) : GoogleAuthState
}
