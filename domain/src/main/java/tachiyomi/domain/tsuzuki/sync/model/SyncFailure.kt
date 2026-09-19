package tachiyomi.domain.tsuzuki.sync.model

data class SyncFailure(
    val reason: SyncFailureReason,
    val retryAfterMillis: Long? = null,
) {
    init {
        require(retryAfterMillis == null || retryAfterMillis >= 0) {
            "Sync retry delay must not be negative"
        }
    }
}

enum class SyncFailureReason {
    AUTHORIZATION_REQUIRED,
    NETWORK_UNAVAILABLE,
    REMOTE_UNAVAILABLE,
    REMOTE_ACCESS_DENIED,
    REMOTE_NOT_FOUND,
    REMOTE_CHANGED,
    RATE_LIMITED,
    MALFORMED_DOCUMENT,
    MALFORMED_REMOTE_DOCUMENT,
    UNSUPPORTED_SCHEMA,
    CONFLICT_PENDING,
    UNKNOWN,
}
