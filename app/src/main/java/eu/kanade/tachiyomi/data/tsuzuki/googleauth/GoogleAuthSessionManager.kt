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
    private val restoreMutex = Mutex()

    private var session: GoogleAuthorizationSession? = null

    val state: StateFlow<GoogleAuthState> = stateMachine.state

    suspend fun restore() {
        restoreMutex.withLock {
            session = null
            stateMachine.beginRestore()

            val accountHint = runCatching {
                accountHintStore.read()
            }.getOrNull()

            if (accountHint == null) {
                stateMachine.signOut()
                return@withLock
            }

            when (val result = authorizationPlatform.authorize(accountHint)) {
                is GoogleAuthorizationPlatformResult.Authorized -> {
                    session = result.session
                    runCatching {
                        accountHintStore.write(result.session.account)
                    }
                    stateMachine.complete(
                        GoogleAuthorizationResult.Authorized(result.session.account),
                    )
                }

                is GoogleAuthorizationPlatformResult.UserActionRequired -> {
                    stateMachine.complete(
                        GoogleAuthorizationResult.AuthorizationRequired(
                            result.account ?: accountHint,
                        ),
                    )
                }

                is GoogleAuthorizationPlatformResult.RecoverableFailure -> {
                    if (result.failure.reason.requiresReauthorization()) {
                        stateMachine.complete(
                            GoogleAuthorizationResult.AuthorizationRequired(
                                result.account ?: accountHint,
                            ),
                        )
                    } else {
                        stateMachine.complete(
                            GoogleAuthorizationResult.RecoverableFailure(
                                failure = result.failure,
                                account = result.account ?: accountHint,
                            ),
                        )
                    }
                }

                is GoogleAuthorizationPlatformResult.Failure -> {
                    if (result.failure.reason.requiresReauthorization()) {
                        stateMachine.complete(
                            GoogleAuthorizationResult.AuthorizationRequired(accountHint),
                        )
                    } else {
                        stateMachine.complete(
                            GoogleAuthorizationResult.Failure(result.failure),
                        )
                    }
                }

                GoogleAuthorizationPlatformResult.Cancelled -> {
                    stateMachine.complete(
                        GoogleAuthorizationResult.AuthorizationRequired(accountHint),
                    )
                }
            }
        }
    }

    internal fun currentSession(): GoogleAuthorizationSession? = session

    private fun GoogleAuthFailureReason.requiresReauthorization(): Boolean {
        return this == GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE ||
            this == GoogleAuthFailureReason.AUTHORIZATION_REJECTED
    }
}
