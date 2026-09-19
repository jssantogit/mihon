package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailure

sealed interface GoogleAuthorizationPlatformResult {
    data class Authorized(
        val session: GoogleAuthorizationSession,
    ) : GoogleAuthorizationPlatformResult

    data class UserActionRequired(
        val action: GoogleAuthorizationUserAction,
        val account: GoogleAccountIdentity? = null,
    ) : GoogleAuthorizationPlatformResult

    data class RecoverableFailure(
        val failure: GoogleAuthFailure,
        val account: GoogleAccountIdentity? = null,
    ) : GoogleAuthorizationPlatformResult

    data class Failure(
        val failure: GoogleAuthFailure,
    ) : GoogleAuthorizationPlatformResult

    data object Cancelled : GoogleAuthorizationPlatformResult
}

sealed interface GoogleAuthorizationOperationResult {
    data object Success : GoogleAuthorizationOperationResult

    data class RecoverableFailure(
        val failure: GoogleAuthFailure,
    ) : GoogleAuthorizationOperationResult

    data class Failure(
        val failure: GoogleAuthFailure,
    ) : GoogleAuthorizationOperationResult
}

interface GoogleAuthorizationUserAction
