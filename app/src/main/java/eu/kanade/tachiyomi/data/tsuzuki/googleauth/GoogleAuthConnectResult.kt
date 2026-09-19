package eu.kanade.tachiyomi.data.tsuzuki.googleauth

sealed interface GoogleAuthConnectResult {
    data object Completed : GoogleAuthConnectResult

    data class UserActionRequired(
        val action: GoogleAuthorizationUserAction,
    ) : GoogleAuthConnectResult
}
