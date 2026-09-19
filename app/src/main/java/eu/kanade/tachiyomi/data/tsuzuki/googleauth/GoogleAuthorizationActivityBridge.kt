package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

interface GoogleAuthorizationActivityBridge {
    fun createRequest(action: GoogleAuthorizationUserAction): IntentSenderRequest

    fun resolve(
        activityResult: ActivityResult,
        accountHint: GoogleAccountIdentity?,
    ): GoogleAuthorizationPlatformResult
}
