package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.service.SyncRetryPolicy

class SyncRetryPolicyTest {

    private val policy = SyncRetryPolicy()

    @Test
    fun `case 1 - retryable failures use bounded progressive backoff`() {
        val failure = SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE)

        policy.nextAttemptAt(100, 0, failure) shouldBe 60_100
        policy.nextAttemptAt(100, 1, failure) shouldBe 300_100
        policy.nextAttemptAt(100, 4, failure) shouldBe 21_600_100
        policy.nextAttemptAt(100, 99, failure) shouldBe 43_200_100
    }

    @Test
    fun `case 2 - server retry after is respected but capped`() {
        val twentyHours = 20 * 60 * 60_000L
        val fortyEightHours = 48 * 60 * 60_000L

        policy.nextAttemptAt(
            nowEpochMillis = 100,
            currentAttemptCount = 0,
            failure = SyncFailure(
                reason = SyncFailureReason.RATE_LIMITED,
                retryAfterMillis = twentyHours,
            ),
        ) shouldBe 72_000_100

        policy.nextAttemptAt(
            nowEpochMillis = 100,
            currentAttemptCount = 0,
            failure = SyncFailure(
                reason = SyncFailureReason.RATE_LIMITED,
                retryAfterMillis = fortyEightHours,
            ),
        ) shouldBe 86_400_100
    }

    @Test
    fun `case 3 - user action and malformed data do not schedule automatic retry`() {
        policy.nextAttemptAt(
            nowEpochMillis = 100,
            currentAttemptCount = 0,
            failure = SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
        ) shouldBe null

        policy.nextAttemptAt(
            nowEpochMillis = 100,
            currentAttemptCount = 0,
            failure = SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
        ) shouldBe null
    }

    @Test
    fun `case 4 - retry timestamp saturates instead of overflowing`() {
        policy.nextAttemptAt(
            nowEpochMillis = Long.MAX_VALUE - 1,
            currentAttemptCount = 0,
            failure = SyncFailure(SyncFailureReason.REMOTE_UNAVAILABLE),
        ) shouldBe Long.MAX_VALUE
    }
}
