package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

class GoogleAuthorizationSession internal constructor(
    val account: GoogleAccountIdentity,
    internal val accessToken: String,
) {
    init {
        require(accessToken.isNotBlank()) { "Google access token must not be blank" }
    }

    override fun toString(): String {
        return "GoogleAuthorizationSession(account=$account, accessToken=[REDACTED])"
    }
}
