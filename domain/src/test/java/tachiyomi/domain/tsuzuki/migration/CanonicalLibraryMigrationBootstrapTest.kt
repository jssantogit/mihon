package tachiyomi.domain.tsuzuki.migration

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.migration.model.MihonLibrarySnapshot
import tachiyomi.domain.tsuzuki.migration.repository.CanonicalLibraryMigrationStateRepository
import tachiyomi.domain.tsuzuki.migration.service.MihonLibraryGateway
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class CanonicalLibraryMigrationBootstrapTest {

    @Test
    fun `completed migration never reimports legacy favorites`() = runTest {
        val state = FakeMigrationState(completed = true)
        val gateway = CountingGateway()
        val interactor = MigrateMihonLibraryToCanonical(
            gateway = gateway,
            sourceTitleMappingRepository = FakeSourceMappings(),
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = FakeTitles(),
                idFactory = { "unused" },
                clock = { 100L },
            ),
            canonicalLibraryRepository = FakeLibrary(),
            migrationStateRepository = state,
            idFactory = { "unused" },
            clock = { 100L },
        )

        val report = interactor.execute()

        gateway.callCount shouldBe 0
        report.totalProcessed shouldBe 0
        state.markCount shouldBe 0
    }

    @Test
    fun `successful first migration marks bootstrap completed`() = runTest {
        val state = FakeMigrationState(completed = false)
        val interactor = MigrateMihonLibraryToCanonical(
            gateway = CountingGateway(emptyList()),
            sourceTitleMappingRepository = FakeSourceMappings(),
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = FakeTitles(),
                idFactory = { "unused" },
                clock = { 100L },
            ),
            canonicalLibraryRepository = FakeLibrary(),
            migrationStateRepository = state,
            idFactory = { "unused" },
            clock = { 100L },
        )

        interactor.execute()

        state.completed shouldBe true
        state.markCount shouldBe 1
    }

    private class FakeMigrationState(
        var completed: Boolean,
    ) : CanonicalLibraryMigrationStateRepository {
        var markCount = 0

        override suspend fun isCompleted() = completed

        override suspend fun markCompleted() {
            completed = true
            markCount++
        }
    }

    private class CountingGateway(
        private val snapshots: List<MihonLibrarySnapshot> = emptyList(),
    ) : MihonLibraryGateway {
        var callCount = 0
        override suspend fun snapshot(): List<MihonLibrarySnapshot> {
            callCount++
            return snapshots
        }
    }

    private class FakeLibrary : CanonicalLibraryRepository {
        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = null
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(emptyList())
        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = MutableStateFlow(emptyList())
        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit
        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeSourceMappings : SourceTitleMappingRepository {
        override suspend fun getAll(): List<SourceTitleMapping> = emptyList()
        override fun getAllAsFlow(): Flow<List<SourceTitleMapping>> = MutableStateFlow(emptyList())
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) = emptyList<SourceTitleMapping>()
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String) = MutableStateFlow(emptyList<SourceTitleMapping>())
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null
        override suspend fun upsert(mapping: SourceTitleMapping) = Unit
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }

    private class FakeTitles : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = null
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(null)
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(title: CanonicalTitle, identity: ExternalIdentity) = title
        override suspend fun insert(title: CanonicalTitle) = Unit
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }
}
