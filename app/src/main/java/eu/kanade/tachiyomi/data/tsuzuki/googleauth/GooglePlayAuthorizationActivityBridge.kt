package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailure
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class GooglePlayAuthorizationActivityBridge(
    context: Context,
) : GoogleAuthorizationActivityBridge {

    private val authorizationClient = Identity.getAuthorizationClient(context)

    override fun createRequest(action: GoogleAuthorizationUserAction): IntentSenderRequest {
        val pendingAction = action as? GooglePendingAuthorizationAction
            ?: error("Unsupported Google authorization user action")

        return IntentSenderRequest.Builder(pendingAction.pendingIntent.intentSender)
            .build()
    }

    override fun resolve(
        activityResult: ActivityResult,
        accountHint: GoogleAccountIdentity?,
    ): GoogleAuthorizationPlatformResult {
        if (activityResult.resultCode != Activity.RESULT_OK) {
            return GoogleAuthorizationPlatformResult.Cancelled
        }

        val data = activityResult.data
            ?: return GoogleAuthorizationPlatformResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.AUTHORIZATION_REJECTED,
                    message = "Google authorization returned no result data",
                ),
            )

        return try {
            authorizationClient.getAuthorizationResultFromIntent(data)
                .toPlatformResult(accountHint)
        } catch (e: ApiException) {
            mapAuthorizationApiStatus(e.statusCode, accountHint)
        } catch (_: Throwable) {
            GoogleAuthorizationPlatformResult.Failure(
                GoogleAuthFailure(
                    reason = GoogleAuthFailureReason.UNKNOWN,
                    message = "Google authorization result could not be read",
                ),
            )
        }
    }
}
