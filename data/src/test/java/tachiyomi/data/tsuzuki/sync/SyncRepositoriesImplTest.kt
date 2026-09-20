package tachiyomi.data.tsuzuki.sync

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncOutboxEntry
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncStoredState
import java.nio.file.Files

class SyncRepositoriesImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var outbox: SyncOutboxRepositoryImpl
    private lateinit var state: SyncStateRepositoryImpl
    private lateinit var conflicts: SyncConflictRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-sync-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        SyncRepositoriesImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())

        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )

        outbox = SyncOutboxRepositoryImpl(database)
        state = SyncStateRepositoryImpl(database)
        conflicts = SyncConflictRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (originalNativeLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalNativeLibraryPath)
        }
        nativeLibraryDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `outbox coalesces dirty documents and persists retry metadata`() = runBlocking<Unit> {
        outbox.markDirty(SyncDocumentKind.LIBRARY, 100L)
        outbox.recordFailure(SyncDocumentKind.LIBRARY, 500L)
        outbox.markDirty(SyncDocumentKind.COLLECTIONS, 50L)

        outbox.get(SyncDocumentKind.LIBRARY) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 100L,
            attemptCount = 1,
            nextAttemptAtEpochMillis = 500L,
        )
        outbox.getPending(nowEpochMillis = 499L, limit = 10) shouldContainExactly listOf(
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.COLLECTIONS,
                enqueuedAtEpochMillis = 50L,
            ),
        )

        outbox.markDirty(SyncDocumentKind.LIBRARY, 200L)
        outbox.get(SyncDocumentKind.LIBRARY) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 100L,
        )

        outbox.getPending(nowEpochMillis = 499L, limit = 10).map { it.documentKind } shouldContainExactly
            listOf(SyncDocumentKind.COLLECTIONS, SyncDocumentKind.LIBRARY)

        outbox.clear(SyncDocumentKind.LIBRARY)
        outbox.get(SyncDocumentKind.LIBRARY) shouldBe null
    }

    @Test
    fun `domain writes transactionally queue their logical sync documents`() = runBlocking<Unit> {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Tsuzuki",
            identityState = "SOURCE_ONLY",
            createdAt = 10L,
            updatedAt = 10L,
        )

        outbox.get(SyncDocumentKind.LIBRARY)?.documentKind shouldBe SyncDocumentKind.LIBRARY
        outbox.get(SyncDocumentKind.SOURCE_MAPPINGS)?.documentKind shouldBe SyncDocumentKind.SOURCE_MAPPINGS

        outbox.clear(SyncDocumentKind.LIBRARY)
        outbox.clear(SyncDocumentKind.SOURCE_MAPPINGS)

        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = "title-1",
            status = "READING",
            favorite = true,
            addedAt = 10L,
            updatedAt = 20L,
        )
        outbox.get(SyncDocumentKind.LIBRARY)?.documentKind shouldBe SyncDocumentKind.LIBRARY

        database.tsuzuki_source_preferencesQueries.insertPreference(
            language = "en",
            sourceId = 7L,
            position = 0L,
        )
        outbox.get(SyncDocumentKind.SOURCE_MAPPINGS)?.documentKind shouldBe SyncDocumentKind.SOURCE_MAPPINGS

        database.tsuzuki_collectionsQueries.upsertTsuzukiCollection(
            id = "collection-1",
            title = "Favorites",
            origin = "USER",
            sortOrder = 0L,
            schemaVersion = 1L,
            revision = 0L,
            createdAt = 10L,
            updatedAt = 10L,
            deletedAt = null,
        )
        outbox.get(SyncDocumentKind.COLLECTIONS)?.documentKind shouldBe SyncDocumentKind.COLLECTIONS
    }

    @Test
    fun `repeated library upsert resets retry state without duplicating its outbox document`() = runBlocking<Unit> {
        seedTitleWithoutPendingOutbox()
        outbox.markDirty(SyncDocumentKind.LIBRARY, 100L)
        outbox.recordFailure(SyncDocumentKind.LIBRARY, 500L)

        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = "title-1",
            status = "READING",
            favorite = true,
            addedAt = 10L,
            updatedAt = 20L,
        )
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = "title-1",
            status = "COMPLETED",
            favorite = false,
            addedAt = 10L,
            updatedAt = 30L,
        )

        outbox.get(SyncDocumentKind.LIBRARY) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 100L,
        )
        outbox.getPending(nowEpochMillis = 0L, limit = 10) shouldContainExactly listOf(
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.LIBRARY,
                enqueuedAtEpochMillis = 100L,
            ),
        )
    }

    @Test
    fun `repeated source mapping upsert resets retry state without duplicating its outbox document`() =
        runBlocking<Unit> {
        seedTitleWithoutPendingOutbox()
        outbox.markDirty(SyncDocumentKind.SOURCE_MAPPINGS, 100L)
        outbox.recordFailure(SyncDocumentKind.SOURCE_MAPPINGS, 500L)

        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-1",
            canonicalTitleId = "title-1",
            mihonMangaId = null,
            sourceId = 7L,
            sourceUrl = "/title",
            language = "en",
            matchConfidence = null,
            verifiedByUser = false,
            availability = "AVAILABLE",
            preferredOverride = false,
            createdAt = 10L,
            updatedAt = 20L,
        )
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-1",
            canonicalTitleId = "title-1",
            mihonMangaId = 42L,
            sourceId = 7L,
            sourceUrl = "/title",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = "AVAILABLE",
            preferredOverride = true,
            createdAt = 10L,
            updatedAt = 30L,
        )

        outbox.get(SyncDocumentKind.SOURCE_MAPPINGS) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.SOURCE_MAPPINGS,
            enqueuedAtEpochMillis = 100L,
        )
        outbox.getPending(nowEpochMillis = 0L, limit = 10) shouldContainExactly listOf(
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.SOURCE_MAPPINGS,
                enqueuedAtEpochMillis = 100L,
            ),
        )
    }

    @Test
    fun `repeated collection upsert resets retry state without duplicating its outbox document`() = runBlocking<Unit> {
        outbox.markDirty(SyncDocumentKind.COLLECTIONS, 100L)
        outbox.recordFailure(SyncDocumentKind.COLLECTIONS, 500L)

        database.tsuzuki_collectionsQueries.upsertTsuzukiCollection(
            id = "collection-1",
            title = "Favorites",
            origin = "USER",
            sortOrder = 0L,
            schemaVersion = 1L,
            revision = 0L,
            createdAt = 10L,
            updatedAt = 20L,
            deletedAt = null,
        )
        database.tsuzuki_collectionsQueries.upsertTsuzukiCollection(
            id = "collection-1",
            title = "Reading",
            origin = "USER",
            sortOrder = 1L,
            schemaVersion = 1L,
            revision = 1L,
            createdAt = 10L,
            updatedAt = 30L,
            deletedAt = null,
        )

        outbox.get(SyncDocumentKind.COLLECTIONS) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.COLLECTIONS,
            enqueuedAtEpochMillis = 100L,
        )
        outbox.getPending(nowEpochMillis = 0L, limit = 10) shouldContainExactly listOf(
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.COLLECTIONS,
                enqueuedAtEpochMillis = 100L,
            ),
        )
    }

    @Test
    fun `repeated title upsert resets both retry states and keeps one outbox document per kind`() = runBlocking<Unit> {
        outbox.markDirty(SyncDocumentKind.LIBRARY, 100L)
        outbox.recordFailure(SyncDocumentKind.LIBRARY, 500L)
        outbox.markDirty(SyncDocumentKind.SOURCE_MAPPINGS, 100L)
        outbox.recordFailure(SyncDocumentKind.SOURCE_MAPPINGS, 500L)

        database.tsuzuki_titlesQueries.upsertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Tsuzuki",
            identityState = "SOURCE_ONLY",
            createdAt = 10L,
            updatedAt = 20L,
        )
        database.tsuzuki_titlesQueries.upsertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Tsuzuki Updated",
            identityState = "VERIFIED",
            createdAt = 10L,
            updatedAt = 30L,
        )

        outbox.get(SyncDocumentKind.LIBRARY) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.LIBRARY,
            enqueuedAtEpochMillis = 100L,
        )
        outbox.get(SyncDocumentKind.SOURCE_MAPPINGS) shouldBe SyncOutboxEntry(
            documentKind = SyncDocumentKind.SOURCE_MAPPINGS,
            enqueuedAtEpochMillis = 100L,
        )
        outbox.getPending(nowEpochMillis = 0L, limit = 10) shouldContainExactly listOf(
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.LIBRARY,
                enqueuedAtEpochMillis = 100L,
            ),
            SyncOutboxEntry(
                documentKind = SyncDocumentKind.SOURCE_MAPPINGS,
                enqueuedAtEpochMillis = 100L,
            ),
        )
    }

    @Test
    fun `accepted base and remote revision round trip without becoming domain authority`() = runBlocking<Unit> {
        val document = SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = SyncDocumentKind.SOURCE_MAPPINGS,
            revision = SyncRevision(deviceId = "device-a", sequence = 7L),
            generatedAtEpochMillis = 1_000L,
            records = emptyMap(),
        )
        val stored = SyncStoredState(
            documentKind = SyncDocumentKind.SOURCE_MAPPINGS,
            acceptedBase = document,
            remoteRevision = SyncRemoteRevision(
                remoteId = "drive-file",
                revisionToken = "42",
                modifiedAtEpochMillis = 1_100L,
            ),
            lastSuccessfulSyncAtEpochMillis = 1_200L,
        )

        state.put(stored)

        state.get(SyncDocumentKind.SOURCE_MAPPINGS) shouldBe stored

        state.clear(SyncDocumentKind.SOURCE_MAPPINGS)
        state.get(SyncDocumentKind.SOURCE_MAPPINGS) shouldBe null
    }

    @Test
    fun `conflict ledger replaces one logical document atomically and preserves order`() = runBlocking<Unit> {
        val first = conflict("list-1", "title", "Local", "Remote")
        val second = conflict("list-2", "sort", "POPULARITY", "TITLE")

        conflicts.replaceForDocument(
            documentKind = SyncDocumentKind.COLLECTIONS,
            conflicts = listOf(first, second),
            createdAtEpochMillis = 2_000L,
        )

        conflicts.getForDocument(SyncDocumentKind.COLLECTIONS).map { it.conflict } shouldContainExactly
            listOf(first, second)
        conflicts.getForDocument(SyncDocumentKind.COLLECTIONS).map { it.createdAtEpochMillis } shouldContainExactly
            listOf(2_000L, 2_000L)

        conflicts.replaceForDocument(
            documentKind = SyncDocumentKind.COLLECTIONS,
            conflicts = listOf(second),
            createdAtEpochMillis = 3_000L,
        )

        conflicts.getForDocument(SyncDocumentKind.COLLECTIONS).map { it.conflict } shouldContainExactly
            listOf(second)

        conflicts.clearForDocument(SyncDocumentKind.COLLECTIONS)
        conflicts.getForDocument(SyncDocumentKind.COLLECTIONS) shouldBe emptyList()
    }

    private fun conflict(
        recordId: String,
        property: String,
        local: String,
        remote: String,
    ) = SyncConflict(
        documentKind = SyncDocumentKind.COLLECTIONS,
        recordId = recordId,
        propertyPath = listOf(property),
        kind = SyncConflictKind.FIELD_DIVERGENCE,
        base = SyncConflictValue.Missing,
        local = SyncConflictValue.Present(JsonPrimitive(local)),
        remote = SyncConflictValue.Present(JsonPrimitive(remote)),
    )

    private suspend fun seedTitleWithoutPendingOutbox() {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Tsuzuki",
            identityState = "SOURCE_ONLY",
            createdAt = 10L,
            updatedAt = 10L,
        )
        outbox.clear(SyncDocumentKind.LIBRARY)
        outbox.clear(SyncDocumentKind.SOURCE_MAPPINGS)
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
