package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverride
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverrideKind
import tachiyomi.domain.tsuzuki.chapter.repository.ChapterOverrideRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class ChapterOverridesSyncAdapterTest {

    @Test
    fun `manual overrides and fallback preferences round trip without device local ids`() = runTest {
        val manual = ChapterOverride(
            id = "repair-73",
            canonicalTitleId = "title-1",
            canonicalChapterKey = "01|0000000073|9999999999|00|",
            sourceId = 42L,
            sourceTitleUrl = "/manga/title",
            sourceChapterId = "chapter-73",
            kind = ChapterOverrideKind.SOURCE_CHAPTER_MAPPING,
            payloadJson = """{"canonicalDisplayNumber":"73"}""",
            createdAt = 10L,
            updatedAt = 20L,
        )
        val sourceOverrides = FakeOverrideRepository(mutableMapOf(manual.id to manual))
        val sourcePreferences = FakeReaderPreferenceRepository(
            mutableMapOf(
                "title-1" to CanonicalReaderPreference(
                    canonicalTitleId = "title-1",
                    automaticFallback = true,
                    updatedAt = 30L,
                ),
            ),
        )

        val exported = adapter(sourceOverrides, sourcePreferences).exportDocument()

        exported.kind shouldBe SyncDocumentKind.CHAPTER_OVERRIDES
        exported.records.keys.toList() shouldContainExactly listOf(
            chapterOverrideSyncRecordId("repair-73"),
            readerPreferenceSyncRecordId("title-1"),
        )
        ("sourceMappingId" in exported.records.getValue(chapterOverrideSyncRecordId("repair-73")).fields) shouldBe
            false
        ("chapterVariantId" in exported.records.getValue(chapterOverrideSyncRecordId("repair-73")).fields) shouldBe
            false

        val targetOverrides = FakeOverrideRepository()
        val targetPreferences = FakeReaderPreferenceRepository()
        adapter(targetOverrides, targetPreferences).applyDocument(exported)

        targetOverrides.getById("repair-73") shouldBe manual
        targetPreferences.get("title-1") shouldBe CanonicalReaderPreference(
            canonicalTitleId = "title-1",
            automaticFallback = true,
            updatedAt = 30L,
        )
    }

    @Test
    fun `reader preference tombstone removes local preference`() = runTest {
        val sourcePreferences = FakeReaderPreferenceRepository(
            mutableMapOf(
                "title-1" to CanonicalReaderPreference(
                    canonicalTitleId = "title-1",
                    automaticFallback = true,
                    updatedAt = 30L,
                ),
            ),
        )
        val exported = adapter(FakeOverrideRepository(), sourcePreferences).exportDocument()
        val preferenceId = readerPreferenceSyncRecordId("title-1")
        val tombstoned = exported.copy(
            records = exported.records + mapOf(
                preferenceId to exported.records.getValue(preferenceId).copy(
                    updatedAtEpochMillis = 40L,
                    deletedAtEpochMillis = 40L,
                ),
            ),
        )

        val targetPreferences = FakeReaderPreferenceRepository(
            mutableMapOf(
                "title-1" to CanonicalReaderPreference(
                    canonicalTitleId = "title-1",
                    automaticFallback = false,
                    updatedAt = 5L,
                ),
            ),
        )

        adapter(FakeOverrideRepository(), targetPreferences).applyDocument(tombstoned)

        targetPreferences.get("title-1") shouldBe null
    }

    private fun adapter(
        overrides: ChapterOverrideRepository,
        preferences: CanonicalReaderPreferenceRepository,
    ) = ChapterOverridesSyncAdapter(
        overrideRepository = overrides,
        readerPreferenceRepository = preferences,
        revisionSource = object : SyncRevisionSource {
            private var sequence = 1L
            override fun nextRevision() = SyncRevision("test-device", sequence++)
        },
        clock = object : SyncClock {
            override fun nowEpochMillis(): Long = 100L
        },
    )

    private class FakeOverrideRepository(
        private val values: MutableMap<String, ChapterOverride> = mutableMapOf(),
    ) : ChapterOverrideRepository {
        override suspend fun getById(id: String): ChapterOverride? = values[id]

        override suspend fun getAll(includeDeleted: Boolean): List<ChapterOverride> =
            values.values
                .filter { includeDeleted || !it.isDeleted }
                .sortedBy(ChapterOverride::id)

        override suspend fun upsert(chapterOverride: ChapterOverride) {
            values[chapterOverride.id] = chapterOverride
        }
    }

    private class FakeReaderPreferenceRepository(
        private val values: MutableMap<String, CanonicalReaderPreference> = mutableMapOf(),
    ) : CanonicalReaderPreferenceRepository {
        override suspend fun get(canonicalTitleId: String): CanonicalReaderPreference? =
            values[canonicalTitleId]

        override suspend fun getAll(): List<CanonicalReaderPreference> =
            values.values.sortedBy(CanonicalReaderPreference::canonicalTitleId)

        override fun observe(canonicalTitleId: String): Flow<CanonicalReaderPreference?> =
            flowOf(values[canonicalTitleId])

        override suspend fun upsert(preference: CanonicalReaderPreference) {
            values[preference.canonicalTitleId] = preference
        }

        override suspend fun delete(canonicalTitleId: String) {
            values.remove(canonicalTitleId)
        }
    }
}
