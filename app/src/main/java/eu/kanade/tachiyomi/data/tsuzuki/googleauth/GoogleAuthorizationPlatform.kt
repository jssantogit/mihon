package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

interface GoogleAuthorizationPlatform {
    suspend fun authorize(accountHint: GoogleAccountIdentity? = null): GoogleAuthorizationPlatformResult

    suspend fun revoke(account: GoogleAccountIdentity): GoogleAuthorizationOperationResult

    suspend fun clearAccessToken(session: GoogleAuthorizationSession): GoogleAuthorizationOperationResult
}
