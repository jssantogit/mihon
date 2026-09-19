package tachiyomi.domain.tsuzuki.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState

class SyncLocalStateContractTest {

    @Test
    fun `case 1 - marking an existing outbox item dirty coalesces instead of duplicating attempts`() {
        val existing = SyncOutboxEntry(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 100,
            attemptCount = 3,
            nextAttemptAtEpochMillis = 500,
        )

        existing.markDirty(enqueuedAtEpochMillis = 200) shouldBe
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.LIBRARY,
                enqueuedAtEpochMillis = 100,
                attemptCount = 0,
                nextAttemptAtEpochMillis = null,
            )
    }

    @Test
    fun `case 2 - retry metadata does not change original enqueue order`() {
        val existing = SyncOutboxEntry(
            documentKind = SyncDocumentKind.SETTINGS,
            enqueuedAtEpochMillis = 100,
        )

        existing.recordFailure(nextAttemptAtEpochMillis = 1_000) shouldBe
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.SETTINGS,
                enqueuedAtEpochMillis = 100,
                attemptCount = 1,
                nextAttemptAtEpochMillis = 1_000,
            )
    }

    @Test
    fun `case 3 - delayed outbox work becomes ready only at retry deadline`() {
        val entry = SyncOutboxEntry(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 100,
            attemptCount = 1,
            nextAttemptAtEpochMillis = 500,
        )

        entry.isReady(499).shouldBeFalse()
        entry.isReady(500).shouldBeTrue()
    }

    @Test
    fun `case 4 - manifest cannot be directly inserted into outbox`() {
        shouldThrow<IllegalArgumentException> {
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.MANIFEST,
                enqueuedAtEpochMillis = 100,
            )
        }
    }

    @Test
    fun `case 5 - stored base must belong to the same logical document`() {
        val libraryDocument = SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = SyncDocumentKind.LIBRARY,
            revision = SyncRevision("device-a", 1),
            generatedAtEpochMillis = 100,
            records = emptyMap(),
        )

        shouldThrow<IllegalArgumentException> {
            SyncStoredState(
                documentKind = SyncDocumentKind.SETTINGS,
                acceptedBase = libraryDocument,
                remoteRevision = null,
                lastSuccessfulSyncAtEpochMillis = null,
            )
        }
    }

    @Test
    fun `case 6 - state may exist before first remote file is created`() {
        val state = SyncStoredState(
            documentKind = SyncDocumentKind.SOURCE_MAPPINGS,
            acceptedBase = null,
            remoteRevision = null,
            lastSuccessfulSyncAtEpochMillis = null,
        )

        state.acceptedBase shouldBe null
        state.remoteRevision shouldBe null
    }
}
