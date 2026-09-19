package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthState

@Inject
@SingleIn(AppScope::class)
class GoogleAuthInteractiveCoordinator(
    private val sessionManager: GoogleAuthSessionManager,
    private val activityBridge: GoogleAuthorizationActivityBridge,
) {
    val state: StateFlow<GoogleAuthState> = sessionManager.state

    suspend fun beginConnect(): GoogleAuthConnectResult {
        return sessionManager.connect()
    }

    fun createRequest(action: GoogleAuthorizationUserAction): IntentSenderRequest {
        return activityBridge.createRequest(action)
    }

    suspend fun complete(activityResult: ActivityResult): GoogleAuthConnectResult {
        val result = activityBridge.resolve(
            activityResult = activityResult,
            accountHint = sessionManager.pendingInteractiveAccountHint(),
        )
        return sessionManager.completeInteractive(result)
    }

    suspend fun cancelPending() {
        sessionManager.cancelInteractive()
    }

    suspend fun disconnect(): GoogleAuthorizationOperationResult {
        return sessionManager.disconnect()
    }
}
