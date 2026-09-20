package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason

class SyncRetryPolicy(
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val maxRetryDelayMillis: Long = DEFAULT_MAX_RETRY_DELAY_MILLIS,
) {

    init {
        require(retryDelaysMillis.isNotEmpty()) {
            "Sync retry policy requires at least one delay"
        }
        require(retryDelaysMillis.all { it > 0 }) {
            "Sync retry delays must be positive"
        }
        require(maxRetryDelayMillis > 0) {
            "Maximum sync retry delay must be positive"
        }
    }

    fun nextAttemptAt(
        nowEpochMillis: Long,
        currentAttemptCount: Int,
        failure: SyncFailure,
    ): Long? {
        require(nowEpochMillis >= 0) {
            "Sync retry current time must not be negative"
        }
        require(currentAttemptCount >= 0) {
            "Sync retry attempt count must not be negative"
        }

        if (!failure.reason.isAutomaticallyRetryable()) {
            return null
        }

        val policyDelay = retryDelaysMillis[
            currentAttemptCount.coerceAtMost(retryDelaysMillis.lastIndex),
        ]
        val serverDelay = failure.retryAfterMillis
            ?.coerceAtMost(maxRetryDelayMillis)
            ?: 0L
        val delay = maxOf(policyDelay, serverDelay)
            .coerceAtMost(maxRetryDelayMillis)

        return if (delay > Long.MAX_VALUE - nowEpochMillis) {
            Long.MAX_VALUE
        } else {
            nowEpochMillis + delay
        }
    }

    private fun SyncFailureReason.isAutomaticallyRetryable(): Boolean {
        return this == SyncFailureReason.NETWORK_UNAVAILABLE ||
            this == SyncFailureReason.LOCAL_STATE_UNAVAILABLE ||
            this == SyncFailureReason.REMOTE_UNAVAILABLE ||
            this == SyncFailureReason.REMOTE_NOT_FOUND ||
            this == SyncFailureReason.REMOTE_CHANGED ||
            this == SyncFailureReason.RATE_LIMITED ||
            this == SyncFailureReason.UNKNOWN
    }

    private companion object {
        val DEFAULT_RETRY_DELAYS_MILLIS = listOf(
            1 * 60_000L,
            5 * 60_000L,
            30 * 60_000L,
            2 * 60 * 60_000L,
            6 * 60 * 60_000L,
            12 * 60 * 60_000L,
        )
        const val DEFAULT_MAX_RETRY_DELAY_MILLIS = 24 * 60 * 60_000L
    }
}
