package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
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
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleMergeConflict
import java.nio.file.Files

class CanonicalTitleMergeRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: CanonicalTitleMergeRepositoryImpl
    private lateinit var titleRepository: CanonicalTitleRepositoryImpl
    private lateinit var libraryRepository: CanonicalLibraryRepositoryImpl
    private lateinit var chapterRepository: CanonicalChapterRepositoryImpl
    private lateinit var readingRepository: CanonicalReadingRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-title-merge-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalTitleMergeRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())

        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
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
        repository = CanonicalTitleMergeRepositoryImpl(database)
        titleRepository = CanonicalTitleRepositoryImpl(database)
        libraryRepository = CanonicalLibraryRepositoryImpl(database)
        chapterRepository = CanonicalChapterRepositoryImpl(database)
        readingRepository = CanonicalReadingRepositoryImpl(database)
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
    fun `verified duplicate title can be rekeyed without losing canonical user state`() = runBlocking<Unit> {
        seedTitle("winner", "Winner")
        seedTitle("duplicate", "Duplicate")
        seedCategory()
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = "duplicate",
            status = LibraryStatus.READING.name,
            favorite = true,
            addedAt = 10L,
            updatedAt = 20L,
        )
        database.tsuzuki_library_categoriesQueries.insertTsuzukiLibraryCategory("duplicate", 1L)
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            canonicalTitleId = "duplicate",
            provider = "kitsu",
            externalId = "1",
            verified = true,
            createdAt = 10L,
        )
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-1",
            canonicalTitleId = "duplicate",
            mihonMangaId = null,
            sourceId = 7L,
            sourceUrl = "/duplicate",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = "AVAILABLE",
            preferredOverride = true,
            createdAt = 10L,
            updatedAt = 20L,
        )
        chapterRepository.upsert(chapter("chapter-37", "duplicate", 37))
        readingRepository.recordCheckpoint(
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-37",
                lastPageRead = 12L,
                updatedAt = 30L,
            ),
            history = CanonicalChapterHistoryUpdate(
                canonicalChapterId = "chapter-37",
                readAt = 30L,
                sessionReadDuration = 90L,
            ),
        )
        database.tsuzuki_content_preferencesQueries.upsertTsuzukiContentPreference(
            canonicalTitleId = "duplicate",
            preferredAddonId = "mangadex",
            updatedAt = 40L,
        )
        database.tsuzuki_content_bindingsQueries.upsertTsuzukiContentBinding(
            id = "binding-1",
            canonicalTitleId = "duplicate",
            addonId = "mangadex",
            providerTitleKey = "md-1",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = "AVAILABLE",
            runtimePayload = byteArrayOf(1),
            createdAt = 10L,
            updatedAt = 20L,
        )
        database.tsuzuki_chapter_evidenceQueries.upsertTsuzukiChapterEvidence(
            id = "evidence-1",
            canonicalTitleId = "duplicate",
            producerKind = "INTEGRATION",
            producerId = "mal",
            externalChapterKey = "37",
            rawLabel = "Chapter 37",
            rawNumber = 37.0,
            volume = null,
            title = null,
            observedAt = 50L,
            confidence = 1.0,
            authorityClass = "EDITORIAL",
            mappedCanonicalChapterId = "chapter-37",
            rawMetadata = byteArrayOf(),
        )
        database.tsuzuki_chapter_update_stateQueries.upsertTsuzukiChapterUpdateState(
            canonicalChapterId = "chapter-37",
            canonicalTitleId = "duplicate",
            firstSeenAt = 50L,
            acknowledgedAt = null,
        )
        database.tsuzuki_canonical_downloadsQueries.upsertTsuzukiCanonicalDownload(
            canonicalChapterId = "chapter-37",
            localUri = "file:///chapter-37.cbz",
            format = "CBZ",
            originatingAddonId = "mangadex",
            originatingOptionKey = "md:37",
            completedAt = 55L,
            checksum = "abc",
        )
        database.tsuzuki_continue_reading_stateQueries.upsertTsuzukiContinueReadingState(
            canonicalTitleId = "duplicate",
            hiddenAt = 60L,
        )

        repository.convergeTo(targetId = "winner", localId = "duplicate")

        titleRepository.getById("duplicate") shouldBe null
        titleRepository.getByExternalIdentity("kitsu", "1")?.id shouldBe "winner"
        libraryRepository.get("winner") shouldBe CanonicalLibraryEntry(
            canonicalTitleId = "winner",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 10L,
            updatedAt = 20L,
        )
        database.tsuzuki_library_categoriesQueries
            .getTsuzukiLibraryCategoriesByTitle("winner") { categoryId, _, _, _ -> categoryId }
            .awaitAsList() shouldContainExactly listOf(1L)
        database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingById("mapping-1") { _, canonicalTitleId, _, _, _, _, _, _, _, _, _, _ ->
                canonicalTitleId
            }
            .awaitAsOneOrNull() shouldBe "winner"
        chapterRepository.getById("chapter-37")?.canonicalTitleId shouldBe "winner"
        readingRepository.getProgress("chapter-37")?.lastPageRead shouldBe 12L
        readingRepository.getHistory("chapter-37")?.totalReadDuration shouldBe 90L
        database.tsuzuki_content_preferencesQueries
            .getTsuzukiContentPreference("winner") { _, preferredAddonId, _ -> preferredAddonId.orEmpty() }
            .awaitAsOneOrNull() shouldBe "mangadex"
        database.tsuzuki_content_bindingsQueries
            .getTsuzukiContentBindingsByTitle("winner") { id, _, _, _, _, _, _, _, _, _ -> id }
            .awaitAsList() shouldContainExactly listOf("binding-1")
        database.tsuzuki_chapter_evidenceQueries
            .getTsuzukiChapterEvidenceByTitle("winner") { id, _, _, _, _, _, _, _, _, _, _, _, _, _ -> id }
            .awaitAsList() shouldContainExactly listOf("evidence-1")
        database.tsuzuki_chapter_update_stateQueries
            .getTsuzukiChapterUpdateStateByTitle("winner") { chapterId, _, _, _ -> chapterId }
            .awaitAsList() shouldContainExactly listOf("chapter-37")
        database.tsuzuki_canonical_downloadsQueries
            .getTsuzukiCanonicalDownload("chapter-37") { _, localUri, _, _, _, _, _ -> localUri }
            .awaitAsOneOrNull() shouldBe "file:///chapter-37.cbz"
        database.tsuzuki_continue_reading_stateQueries
            .getTsuzukiContinueReadingState("winner") { _, hiddenAt -> hiddenAt ?: -1L }
            .awaitAsOneOrNull() shouldBe 60L
    }

    @Test
    fun `missing target is created from local title before rekey`() = runBlocking<Unit> {
        seedTitle("local", "Cross-device title")
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            canonicalTitleId = "local",
            provider = "mal",
            externalId = "99",
            verified = true,
            createdAt = 1L,
        )
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = "local",
            status = LibraryStatus.PLANNING.name,
            favorite = true,
            addedAt = 2L,
            updatedAt = 3L,
        )

        repository.convergeTo(targetId = "cloud-winner", localId = "local")

        titleRepository.getById("local") shouldBe null
        titleRepository.getById("cloud-winner")?.displayTitle shouldBe "Cross-device title"
        titleRepository.getByExternalIdentity("mal", "99")?.id shouldBe "cloud-winner"
        libraryRepository.get("cloud-winner")?.canonicalTitleId shouldBe "cloud-winner"
    }

    @Test
    fun `different preferred addons abort without deleting either title`() = runBlocking<Unit> {
        seedTitle("winner", "Winner")
        seedTitle("duplicate", "Duplicate")
        database.tsuzuki_content_preferencesQueries.upsertTsuzukiContentPreference(
            canonicalTitleId = "winner",
            preferredAddonId = "addon-a",
            updatedAt = 1L,
        )
        database.tsuzuki_content_preferencesQueries.upsertTsuzukiContentPreference(
            canonicalTitleId = "duplicate",
            preferredAddonId = "addon-b",
            updatedAt = 2L,
        )

        shouldThrow<CanonicalTitleMergeConflict.PreferredAddonConflict> {
            repository.convergeTo("winner", "duplicate")
        }

        titleRepository.getById("winner")?.id shouldBe "winner"
        titleRepository.getById("duplicate")?.id shouldBe "duplicate"
    }

    @Test
    fun `incompatible non-default library states abort the whole merge`() = runBlocking<Unit> {
        seedTitle("winner", "Winner")
        seedTitle("duplicate", "Duplicate")
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            "winner",
            LibraryStatus.COMPLETED.name,
            true,
            1L,
            1L,
        )
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            "duplicate",
            LibraryStatus.READING.name,
            true,
            2L,
            2L,
        )
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            "duplicate",
            "kitsu",
            "1",
            true,
            1L,
        )

        shouldThrow<CanonicalTitleMergeConflict.LibraryStatusConflict> {
            repository.convergeTo("winner", "duplicate")
        }

        titleRepository.getById("duplicate")?.id shouldBe "duplicate"
        titleRepository.getByExternalIdentity("kitsu", "1")?.id shouldBe "duplicate"
    }

    @Test
    fun `duplicate structured chapter identity aborts instead of silently merging progress`() = runBlocking<Unit> {
        seedTitle("winner", "Winner")
        seedTitle("duplicate", "Duplicate")
        chapterRepository.upsert(chapter("winner-37", "winner", 37))
        chapterRepository.upsert(chapter("duplicate-37", "duplicate", 37))

        shouldThrow<CanonicalTitleMergeConflict.ChapterIdentityConflict> {
            repository.convergeTo("winner", "duplicate")
        }

        chapterRepository.getById("winner-37")?.canonicalTitleId shouldBe "winner"
        chapterRepository.getById("duplicate-37")?.canonicalTitleId shouldBe "duplicate"
    }

    private suspend fun seedTitle(id: String, displayTitle: String) {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = id,
            displayTitle = displayTitle,
            identityState = "RESOLVED",
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private suspend fun seedCategory() {
        driver.execute(
            null,
            "INSERT INTO categories(_id, name, sort, flags) VALUES (1, 'Favorites', 0, 0)",
            0,
        ).await()
    }

    private fun chapter(id: String, titleId: String, number: Int) = CanonicalChapter(
        id = id,
        canonicalTitleId = titleId,
        displayNumber = number.toString(),
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        confidence = 1.0,
        createdAt = 1L,
        updatedAt = 1L,
        confirmation = CanonicalChapterConfirmation.CONFIRMED,
    )

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
