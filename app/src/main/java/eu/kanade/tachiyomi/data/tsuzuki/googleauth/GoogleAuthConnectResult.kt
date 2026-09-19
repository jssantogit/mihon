package eu.kanade.tachiyomi.data.tsuzuki.googleauth

sealed interface GoogleAuthConnectResult {
    data object Completed : GoogleAuthConnectResult

    data object InProgress : GoogleAuthConnectResult

    data object AccountSelectionRequired : GoogleAuthConnectResult

    data class UserActionRequired(
        val action: GoogleAuthorizationUserAction,
    ) : GoogleAuthConnectResult
}
