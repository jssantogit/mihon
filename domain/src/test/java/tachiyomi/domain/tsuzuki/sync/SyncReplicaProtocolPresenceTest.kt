package tachiyomi.domain.tsuzuki.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class SyncReplicaProtocolPresenceTest {

    @Test
    fun `protocol v2 causal core is present`() {
        val requiredClasses = listOf(
            "tachiyomi.domain.tsuzuki.sync.model.SyncFrontier",
            "tachiyomi.domain.tsuzuki.sync.model.SyncMutation",
            "tachiyomi.domain.tsuzuki.sync.model.SyncReplicaJournal",
            "tachiyomi.domain.tsuzuki.sync.service.SyncReplicaJournalCodec",
            "tachiyomi.domain.tsuzuki.sync.service.SyncDocumentDiffer",
            "tachiyomi.domain.tsuzuki.sync.service.SyncReplicaMaterializer",
        )

        val missing = requiredClasses.filter { className ->
            runCatching { Class.forName(className) }.isFailure
        }

        check(missing.isEmpty()) {
            "Protocol v2 causal core missing: ${missing.joinToString()}"
        }
    }
}
