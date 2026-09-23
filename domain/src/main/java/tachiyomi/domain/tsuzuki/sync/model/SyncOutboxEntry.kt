package tachiyomi.domain.tsuzuki.sync.model

data class SyncOutboxEntry(
    val documentKind: SyncDocumentKind,
    val enqueuedAtEpochMillis: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
) {
    init {
        require(documentKind != SyncDocumentKind.MANIFEST) {
            "Manifest is derived from logical documents and must not be enqueued directly"
        }
        require(enqueuedAtEpochMillis >= 0) {
            "Sync outbox enqueue time must not be negative"
        }
        require(attemptCount >= 0) {
            "Sync outbox attempt count must not be negative"
        }
        require(nextAttemptAtEpochMillis == null || nextAttemptAtEpochMillis >= 0) {
            "Sync outbox next-attempt time must not be negative"
        }
    }

    fun markDirty(enqueuedAtEpochMillis: Long): SyncOutboxEntry {
        require(enqueuedAtEpochMillis >= 0) {
            "Sync outbox enqueue time must not be negative"
        }

        return copy(
            enqueuedAtEpochMillis = minOf(this.enqueuedAtEpochMillis, enqueuedAtEpochMillis),
            attemptCount = 0,
            nextAttemptAtEpochMillis = null,
        )
    }

    fun recordFailure(nextAttemptAtEpochMillis: Long?): SyncOutboxEntry {
        require(nextAttemptAtEpochMillis == null || nextAttemptAtEpochMillis >= 0) {
            "Sync outbox next-attempt time must not be negative"
        }

        return copy(
            attemptCount = attemptCount + 1,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        )
    }

    fun isReady(nowEpochMillis: Long): Boolean {
        require(nowEpochMillis >= 0) { "Sync outbox current time must not be negative" }
        return nextAttemptAtEpochMillis?.let { it <= nowEpochMillis } ?: true
    }
}
