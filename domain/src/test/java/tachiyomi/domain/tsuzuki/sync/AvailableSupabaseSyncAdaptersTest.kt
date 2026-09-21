package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

class AvailableSupabaseSyncAdaptersTest {

    @Test
    fun `target durable Supabase domains exclude source mappings and Drive manifest`() {
        SyncDocumentKind.supabaseDurableKinds shouldBe listOf(
            SyncDocumentKind.TITLES,
            SyncDocumentKind.LIBRARY,
            SyncDocumentKind.READING_PROGRESS,
            SyncDocumentKind.CHAPTER_UPDATE_STATE,
            SyncDocumentKind.CONTINUE_READING_STATE,
            SyncDocumentKind.COLLECTIONS,
            SyncDocumentKind.CHAPTER_OVERRIDES,
            SyncDocumentKind.INTEGRATION_SETTINGS,
            SyncDocumentKind.CONTENT_PREFERENCES,
            SyncDocumentKind.ADDON_STATE,
        )
    }
}
