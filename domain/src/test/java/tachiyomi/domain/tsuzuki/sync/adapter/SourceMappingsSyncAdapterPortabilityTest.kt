package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class SourceMappingsSyncAdapterPortabilityTest {

    @Test
    fun `source mappings omit Mihon ids and preserve target local materialization`() = runTest {
        val title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Canonical Title",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 10L,
            updatedAt = 20L,
        )
        val sourceMappings = FakeMappingRepository(
            mutableListOf(
                mapping(
                    id = "source-local-mapping",
                    mihonMangaId = 111L,
                    language = "en",
                    preferred = true,
                ),
            ),
        )
        val exported = adapter(
            mappings = sourceMappings,
            titles = FakeTitleRepository(mutableMapOf(title.id to title)),
        ).exportDocument()

        val record = exported.records.getValue(sourceMappingSyncRecordId(7L, "/source"))
        ("mihonMangaId" in record.fields) shouldBe false

        val targetMappings = FakeMappingRepository(
            mutableListOf(
                mapping(
                    id = "target-local-mapping",
                    mihonMangaId = 222L,
                    language = "pt-BR",
                    preferred = false,
                ),
            ),
        )
        val targetTitles = FakeTitleRepository()

        adapter(
            mappings = targetMappings,
            titles = targetTitles,
        ).applyDocument(exported)

        targetMappings.getBySource(7L, "/source") shouldBe mapping(
            id = "target-local-mapping",
            mihonMangaId = 222L,
            language = "en",
            preferred = true,
        )
        targetTitles.getById("title-1") shouldBe title
    }

    private fun adapter(
        mappings: SourceTitleMappingRepository,
        titles: CanonicalTitleRepository,
    ) = SourceMappingsSyncAdapter(
        mappingRepository = mappings,
        preferenceRepository = FakePreferenceRepository(),
        titleRepository = titles,
        revisionSource = object : SyncRevisionSource {
            override val deviceId = "test-device"
            private var sequence = 1L
            override fun nextRevision() = SyncRevision(deviceId, sequence++)
        },
        clock = object : SyncClock {
            override fun nowEpochMillis(): Long = 100L
        },
    )

    private fun mapping(
        id: String,
        mihonMangaId: Long?,
        language: String,
        preferred: Boolean,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = mihonMangaId,
        sourceId = 7L,
        sourceUrl = "/source",
        language = language,
        matchConfidence = 0.95,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 30L,
        updatedAt = 40L,
    )

    private class FakeMappingRepository(
        private val values: MutableList<SourceTitleMapping> = mutableListOf(),
    ) : SourceTitleMappingRepository {

        override suspend fun getAll(): List<SourceTitleMapping> = values.toList()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            flowOf(values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            values.removeAll { it.id == mapping.id || it.key == mapping.key }
            values += mapping
        }

        override suspend fun remove(id: String) {
            values.removeAll { it.id == id }
        }

        override suspend fun setPreferredForTitle(
            canonicalTitleId: String,
            mappingId: String?,
            updatedAt: Long,
        ) = Unit
    }

    private class FakePreferenceRepository : ReadingSourcePreferenceRepository {
        override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> = emptyList()

        override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> = flowOf(emptyList())

        override suspend fun getConfiguredLanguages(): List<String> = emptyList()

        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) = Unit
    }

    private class FakeTitleRepository(
        private val values: MutableMap<String, CanonicalTitle> = mutableMapOf(),
    ) : CanonicalTitleRepository {

        override suspend fun getById(id: String): CanonicalTitle? = values[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(values[id])

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            values.putIfAbsent(title.id, title)
            return values.getValue(title.id)
        }

        override suspend fun insert(title: CanonicalTitle) {
            values[title.id] = title
        }

        override suspend fun upsert(title: CanonicalTitle) {
            values[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }
}
