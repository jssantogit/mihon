package tachiyomi.domain.tsuzuki.googleauth.model

data class GoogleAuthFailure(
    val reason: GoogleAuthFailureReason,
    val message: String? = null,
)

enum class GoogleAuthFailureReason {
    NETWORK_UNAVAILABLE,
    GOOGLE_SERVICES_UNAVAILABLE,
    ACCOUNT_UNAVAILABLE,
    AUTHORIZATION_REJECTED,
    CONFIGURATION_ERROR,
    UNKNOWN,
}
