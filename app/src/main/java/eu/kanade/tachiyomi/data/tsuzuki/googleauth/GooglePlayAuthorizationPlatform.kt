package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailure
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class GooglePlayAuthorizationPlatform(
    context: Context,
) : GoogleAuthorizationPlatform {

    private val authorizationClient = Identity.getAuthorizationClient(context)

    override suspend fun authorize(accountHint: GoogleAccountIdentity?): GoogleAuthorizationPlatformResult {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(DRIVE_APPDATA_SCOPES)
                .apply {
                    accountHint?.let { setAccount(it.toAndroidAccount()) }
                }
                .build()

            authorizationClient.authorize(request)
                .awaitResult()
                .toPlatformResult(accountHint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            mapAuthorizationApiStatus(e.statusCode, accountHint)
        } catch (_: Throwable) {
            GoogleAuthorizationPlatformResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.UNKNOWN,
                    message = "Google authorization failed",
                ),
            )
        }
    }

    override suspend fun revoke(account: GoogleAccountIdentity): GoogleAuthorizationOperationResult {
        return try {
            val request = RevokeAccessRequest.builder()
                .setAccount(account.toAndroidAccount())
                .setScopes(DRIVE_APPDATA_SCOPES)
                .build()

            authorizationClient.revokeAccess(request).awaitResult()
            GoogleAuthorizationOperationResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.toOperationFailure()
        } catch (_: Throwable) {
            GoogleAuthorizationOperationResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.UNKNOWN,
                    message = "Google access revocation failed",
                ),
            )
        }
    }

    override suspend fun clearAccessToken(session: GoogleAuthorizationSession): GoogleAuthorizationOperationResult {
        return try {
            val request = ClearTokenRequest.builder()
                .setToken(session.accessToken)
                .build()

            authorizationClient.clearToken(request).awaitResult()
            GoogleAuthorizationOperationResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.toOperationFailure()
        } catch (_: Throwable) {
            GoogleAuthorizationOperationResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.UNKNOWN,
                    message = "Google token cache clear failed",
                ),
            )
        }
    }

    private fun AuthorizationResult.toPlatformResult(
        accountHint: GoogleAccountIdentity?,
    ): GoogleAuthorizationPlatformResult {
        if (hasResolution()) {
            val resolution = pendingIntent
            if (resolution != null) {
                return GoogleAuthorizationPlatformResult.UserActionRequired(
                    action = GooglePendingAuthorizationAction(resolution),
                    account = accountHint,
                )
            }
        }

        val token = accessToken?.takeIf(String::isNotBlank)
            ?: return GoogleAuthorizationPlatformResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.AUTHORIZATION_REJECTED,
                    message = "Google authorization returned no access token",
                ),
            )

        val account = resolvedAccountIdentity() ?: accountHint
            ?: return GoogleAuthorizationPlatformResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE,
                    message = "Google authorization returned no account identity",
                ),
            )

        return GoogleAuthorizationPlatformResult.Authorized(
            GoogleAuthorizationSession(
                account = account,
                accessToken = token,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun AuthorizationResult.resolvedAccountIdentity(): GoogleAccountIdentity? {
        return toGoogleSignInAccount()
            ?.email
            ?.takeIf(String::isNotBlank)
            ?.let(::GoogleAccountIdentity)
    }

    private fun GoogleAccountIdentity.toAndroidAccount(): Account {
        return Account(accountName, GOOGLE_ACCOUNT_TYPE)
    }

    private fun ApiException.toOperationFailure(): GoogleAuthorizationOperationResult {
        val failure = failureForStatus(statusCode)
        return if (failure.reason == GoogleAuthFailureReason.CONFIGURATION_ERROR) {
            GoogleAuthorizationOperationResult.Failure(failure)
        } else {
            GoogleAuthorizationOperationResult.RecoverableFailure(failure)
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (!continuation.isActive) {
                    return@addOnCompleteListener
                }

                val exception = task.exception
                if (exception != null) {
                    continuation.resumeWithException(exception)
                } else {
                    continuation.resume(task.result)
                }
            }
        }
    }
}

internal data class GooglePendingAuthorizationAction(
    val pendingIntent: PendingIntent,
) : GoogleAuthorizationUserAction {
    override fun toString(): String = "GooglePendingAuthorizationAction([REDACTED])"
}

internal fun mapAuthorizationApiStatus(
    statusCode: Int,
    accountHint: GoogleAccountIdentity?,
): GoogleAuthorizationPlatformResult {
    return when (statusCode) {
        CommonStatusCodes.CANCELED -> GoogleAuthorizationPlatformResult.Cancelled
        CommonStatusCodes.DEVELOPER_ERROR -> GoogleAuthorizationPlatformResult.Failure(
            failureForStatus(statusCode),
        )
        else -> GoogleAuthorizationPlatformResult.RecoverableFailure(
            failure = failureForStatus(statusCode),
            account = accountHint,
        )
    }
}

internal fun failureForStatus(statusCode: Int): GoogleAuthFailure {
    return when (statusCode) {
        CommonStatusCodes.NETWORK_ERROR,
        CommonStatusCodes.TIMEOUT,
        -> GoogleAuthFailure(
            reason = GoogleAuthFailureReason.NETWORK_UNAVAILABLE,
            message = "Google authorization is temporarily unavailable because of a network error",
        )

        CommonStatusCodes.API_NOT_CONNECTED,
        CommonStatusCodes.CONNECTION_SUSPENDED_DURING_CALL,
        CommonStatusCodes.RECONNECTION_TIMED_OUT,
        CommonStatusCodes.RECONNECTION_TIMED_OUT_DURING_UPDATE,
        -> GoogleAuthFailure(
            reason = GoogleAuthFailureReason.GOOGLE_SERVICES_UNAVAILABLE,
            message = "Google Play services are temporarily unavailable",
        )

        CommonStatusCodes.SIGN_IN_REQUIRED,
        CommonStatusCodes.INVALID_ACCOUNT,
        -> GoogleAuthFailure(
            reason = GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE,
            message = "The selected Google account is unavailable",
        )

        CommonStatusCodes.DEVELOPER_ERROR -> GoogleAuthFailure(
            reason = GoogleAuthFailureReason.CONFIGURATION_ERROR,
            message = "Google authorization is not configured correctly",
        )

        else -> GoogleAuthFailure(
            reason = GoogleAuthFailureReason.UNKNOWN,
            message = "Google authorization failed",
        )
    }
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"
private val DRIVE_APPDATA_SCOPES = listOf(
    Scope("https://www.googleapis.com/auth/drive.appdata"),
)
