package tachiyomi.domain.tsuzuki.googleauth.model

sealed interface GoogleAuthorizationResult {
    data class Authorized(
        val account: GoogleAccountIdentity,
    ) : GoogleAuthorizationResult

    data class AuthorizationRequired(
        val account: GoogleAccountIdentity? = null,
    ) : GoogleAuthorizationResult

    data class RecoverableFailure(
        val failure: GoogleAuthFailure,
        val account: GoogleAccountIdentity? = null,
    ) : GoogleAuthorizationResult

    data class Failure(
        val failure: GoogleAuthFailure,
    ) : GoogleAuthorizationResult

    data object Cancelled : GoogleAuthorizationResult
}
