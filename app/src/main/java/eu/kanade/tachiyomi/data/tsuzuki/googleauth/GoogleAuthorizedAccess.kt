package eu.kanade.tachiyomi.data.tsuzuki.googleauth

interface GoogleAuthorizedAccess {
    fun accessTokenOrNull(): GoogleAccessToken?

    suspend fun invalidateRejectedAccessToken(): GoogleAuthorizationOperationResult
}

class GoogleAccessToken internal constructor(
    internal val value: String,
) {
    init {
        require(value.isNotBlank()) { "Google access token must not be blank" }
    }

    override fun toString(): String = "GoogleAccessToken([REDACTED])"
}
