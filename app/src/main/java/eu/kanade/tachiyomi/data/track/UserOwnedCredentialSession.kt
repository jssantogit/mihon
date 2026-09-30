package eu.kanade.tachiyomi.data.track

internal fun userOwnedCredentialSessionActive(
    baseLoggedIn: Boolean,
    vararg credentials: String,
): Boolean = baseLoggedIn && credentials.all { it.isNotBlank() }

internal fun personalTokenSessionActive(
    baseLoggedIn: Boolean,
    password: String,
    accessToken: String,
    expectedPasswordMarker: String,
): Boolean = userOwnedCredentialSessionActive(baseLoggedIn, accessToken) &&
    password == expectedPasswordMarker
