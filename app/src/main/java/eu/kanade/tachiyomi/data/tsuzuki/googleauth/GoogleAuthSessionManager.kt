package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthState
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthorizationResult
import tachiyomi.domain.tsuzuki.googleauth.service.GoogleAuthStateMachine

@Inject
@SingleIn(AppScope::class)
class GoogleAuthSessionManager(
    private val authorizationPlatform: GoogleAuthorizationPlatform,
    private val accountHintStore: GoogleAccountHintStore,
) {
    private val stateMachine = GoogleAuthStateMachine(
        initialState = GoogleAuthState.Restoring,
    )
    private val operationMutex = Mutex()

    private var session: GoogleAuthorizationSession? = null
    private var pendingAccountHint: GoogleAccountIdentity? = null

    val state: StateFlow<GoogleAuthState> = stateMachine.state

    suspend fun restore() {
        operationMutex.withLock {
            session = null
            pendingAccountHint = null
            stateMachine.beginRestore()

            val accountHint = readAccountHint()
            if (accountHint == null) {
                stateMachine.signOut()
                return@withLock
            }

            handleRestoreResult(
                result = authorizationPlatform.authorize(accountHint),
                fallbackAccount = accountHint,
            )
        }
    }

    suspend fun connect(): GoogleAuthConnectResult {
        return operationMutex.withLock {
            session = null
            pendingAccountHint = null
            stateMachine.beginConnection()

            val accountHint = readAccountHint()
            handleConnectResult(
                result = authorizationPlatform.authorize(accountHint),
                fallbackAccount = accountHint,
            )
        }
    }

    suspend fun completeInteractive(
        result: GoogleAuthorizationPlatformResult,
    ): GoogleAuthConnectResult {
        return operationMutex.withLock {
            val accountHint = pendingAccountHint ?: readAccountHint()
            pendingAccountHint = null
            handleConnectResult(
                result = result,
                fallbackAccount = accountHint,
            )
        }
    }

    suspend fun disconnect(): GoogleAuthorizationOperationResult {
        return operationMutex.withLock {
            val sessionToClear = session
            val accountToRevoke = sessionToClear?.account ?: state.value.accountOrNull() ?: readAccountHint()

            session = null
            pendingAccountHint = null
            runCatching { accountHintStore.clear() }
            stateMachine.signOut()

            val clearResult = sessionToClear
                ?.let { authorizationPlatform.clearAccessToken(it) }
                ?: GoogleAuthorizationOperationResult.Success

            val revokeResult = accountToRevoke
                ?.let { authorizationPlatform.revoke(it) }
                ?: GoogleAuthorizationOperationResult.Success

            if (revokeResult !is GoogleAuthorizationOperationResult.Success) {
                revokeResult
            } else {
                clearResult
            }
        }
    }

    internal fun currentSession(): GoogleAuthorizationSession? = session

    internal fun pendingInteractiveAccountHint(): GoogleAccountIdentity? = pendingAccountHint

    private fun handleRestoreResult(
        result: GoogleAuthorizationPlatformResult,
        fallbackAccount: GoogleAccountIdentity,
    ) {
        when (result) {
            is GoogleAuthorizationPlatformResult.Authorized -> acceptSession(result.session)

            is GoogleAuthorizationPlatformResult.UserActionRequired -> {
                stateMachine.complete(
                    GoogleAuthorizationResult.AuthorizationRequired(
                        result.account ?: fallbackAccount,
                    ),
                )
            }

            is GoogleAuthorizationPlatformResult.RecoverableFailure -> {
                completeFailure(
                    failureReason = result.failure.reason,
                    recoverableResult = GoogleAuthorizationResult.RecoverableFailure(
                        failure = result.failure,
                        account = result.account ?: fallbackAccount,
                    ),
                    account = result.account ?: fallbackAccount,
                )
            }

            is GoogleAuthorizationPlatformResult.Failure -> {
                completeFailure(
                    failureReason = result.failure.reason,
                    failureResult = GoogleAuthorizationResult.Failure(result.failure),
                    account = fallbackAccount,
                )
            }

            GoogleAuthorizationPlatformResult.Cancelled -> {
                stateMachine.complete(
                    GoogleAuthorizationResult.AuthorizationRequired(fallbackAccount),
                )
            }
        }
    }

    private fun handleConnectResult(
        result: GoogleAuthorizationPlatformResult,
        fallbackAccount: GoogleAccountIdentity?,
    ): GoogleAuthConnectResult {
        return when (result) {
            is GoogleAuthorizationPlatformResult.Authorized -> {
                acceptSession(result.session)
                GoogleAuthConnectResult.Completed
            }

            is GoogleAuthorizationPlatformResult.UserActionRequired -> {
                pendingAccountHint = result.account ?: fallbackAccount
                GoogleAuthConnectResult.UserActionRequired(result.action)
            }

            is GoogleAuthorizationPlatformResult.RecoverableFailure -> {
                completeFailure(
                    failureReason = result.failure.reason,
                    recoverableResult = GoogleAuthorizationResult.RecoverableFailure(
                        failure = result.failure,
                        account = result.account ?: fallbackAccount,
                    ),
                    account = result.account ?: fallbackAccount,
                )
                GoogleAuthConnectResult.Completed
            }

            is GoogleAuthorizationPlatformResult.Failure -> {
                completeFailure(
                    failureReason = result.failure.reason,
                    failureResult = GoogleAuthorizationResult.Failure(result.failure),
                    account = fallbackAccount,
                )
                GoogleAuthConnectResult.Completed
            }

            GoogleAuthorizationPlatformResult.Cancelled -> {
                stateMachine.complete(GoogleAuthorizationResult.Cancelled)
                GoogleAuthConnectResult.Completed
            }
        }
    }

    private fun acceptSession(authorizedSession: GoogleAuthorizationSession) {
        session = authorizedSession
        pendingAccountHint = null
        runCatching {
            accountHintStore.write(authorizedSession.account)
        }
        stateMachine.complete(
            GoogleAuthorizationResult.Authorized(authorizedSession.account),
        )
    }

    private fun completeFailure(
        failureReason: GoogleAuthFailureReason,
        account: GoogleAccountIdentity?,
        recoverableResult: GoogleAuthorizationResult.RecoverableFailure? = null,
        failureResult: GoogleAuthorizationResult.Failure? = null,
    ) {
        if (failureReason.requiresReauthorization()) {
            if (failureReason == GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE) {
                runCatching { accountHintStore.clear() }
            }
            stateMachine.complete(
                GoogleAuthorizationResult.AuthorizationRequired(account),
            )
            return
        }

        when {
            recoverableResult != null -> stateMachine.complete(recoverableResult)
            failureResult != null -> stateMachine.complete(failureResult)
            else -> error("Google authorization failure result is missing")
        }
    }

    private fun readAccountHint(): GoogleAccountIdentity? {
        return runCatching {
            accountHintStore.read()
        }.getOrNull()
    }

    private fun GoogleAuthFailureReason.requiresReauthorization(): Boolean {
        return this == GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE ||
            this == GoogleAuthFailureReason.AUTHORIZATION_REJECTED
    }

    private fun GoogleAuthState.accountOrNull(): GoogleAccountIdentity? {
        return when (this) {
            is GoogleAuthState.Connected -> account
            is GoogleAuthState.AuthorizationRequired -> account
            is GoogleAuthState.RecoverableFailure -> account
            GoogleAuthState.Restoring,
            GoogleAuthState.SignedOut,
            GoogleAuthState.Connecting,
            is GoogleAuthState.Error,
            -> null
        }
    }
}
