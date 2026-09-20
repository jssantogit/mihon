package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
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
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import java.nio.file.Files
import java.util.Date

class CanonicalHistoryViewTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null
    private var expectedMangaId: Long = -1L
    private var expectedChapterId: Long = -1L

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-history-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalHistoryViewTest::class.java
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
        seedCanonicalHistory()
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
    fun `canonical history is visible once and last read variant controls operational navigation`() =
        runBlocking<Unit> {
            val rows = database.historyViewQueries.history(
                query = "",
                mapper = { _, mangaId, chapterId, title, _, sourceId, _, _, _, readAt, readDuration ->
                    HistoryRow(
                        mangaId = mangaId,
                        chapterId = chapterId,
                        title = title,
                        sourceId = sourceId,
                        readAt = readAt,
                        readDuration = readDuration,
                    )
                },
            ).awaitAsList()

            rows shouldContainExactly listOf(
                HistoryRow(
                    mangaId = expectedMangaId,
                    chapterId = expectedChapterId,
                    title = "Canonical Title",
                    sourceId = 1L,
                    readAt = 3_000L,
                    readDuration = 45L,
                ),
            )
        }

    @Test
    fun `canonical history duration is authoritative`() = runBlocking {
        HistoryRepositoryImpl(database).getTotalReadDuration() shouldBe 45L
    }

    @Test
    fun `resetting visible history clears canonical last read even when legacy history id is used`() =
        runBlocking<Unit> {
            database.historyQueries.upsert(
                chapterId = expectedChapterId,
                readAt = Date(3_000L),
                time_read = 5L,
            )
            val historyId = database.historyViewQueries.getLatestHistory(
                mapper = { id, _, _, _, _, _, _, _, _, _, _ -> id },
            ).awaitAsOne()

            HistoryRepositoryImpl(database).resetHistory(historyId)

            visibleHistoryRows() shouldBe emptyList()
        }

    @Test
    fun `resetting history by operational manga clears the canonical title history`() = runBlocking<Unit> {
        HistoryRepositoryImpl(database).resetHistoryByMangaId(expectedMangaId)

        visibleHistoryRows() shouldBe emptyList()
    }

    @Test
    fun `deleting all history clears canonical history and duration`() = runBlocking<Unit> {
        val repository = HistoryRepositoryImpl(database)

        repository.deleteAllHistory() shouldBe true

        visibleHistoryRows() shouldBe emptyList()
        repository.getTotalReadDuration() shouldBe 0L
    }

    private suspend fun visibleHistoryRows(): List<HistoryRow> =
        database.historyViewQueries.history(
            query = "",
            mapper = { _, mangaId, chapterId, title, _, sourceId, _, _, _, readAt, readDuration ->
                HistoryRow(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    title = title,
                    sourceId = sourceId,
                    readAt = readAt,
                    readDuration = readDuration,
                )
            },
        ).awaitAsList()

    private suspend fun seedCanonicalHistory() {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Canonical Title",
            identityState = "RESOLVED",
            createdAt = 100L,
            updatedAt = 100L,
        )

        val firstManga = insertManga(sourceId = 1L, url = "/one", title = "Source One")
        val secondManga = insertManga(sourceId = 2L, url = "/two", title = "Source Two")
        val firstChapter = insertChapter(firstManga, "/chapter/1", "Source One Chapter")
        expectedMangaId = firstManga
        expectedChapterId = firstChapter
        val secondChapter = insertChapter(secondManga, "/chapter/1", "Source Two Chapter")

        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-1",
            canonicalTitleId = "title-1",
            mihonMangaId = firstManga,
            sourceId = 1L,
            sourceUrl = "/one",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = "AVAILABLE",
            preferredOverride = false,
            createdAt = 100L,
            updatedAt = 100L,
        )
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-2",
            canonicalTitleId = "title-1",
            mihonMangaId = secondManga,
            sourceId = 2L,
            sourceUrl = "/two",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = "AVAILABLE",
            preferredOverride = true,
            createdAt = 100L,
            updatedAt = 100L,
        )

        val chapters = CanonicalChapterRepositoryImpl(database)
        chapters.upsert(
            CanonicalChapter(
                id = "canonical-chapter-1",
                canonicalTitleId = "title-1",
                displayNumber = "1",
                type = CanonicalChapterType.REGULAR,
                baseNumber = 1,
                confidence = 1.0,
                createdAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )
        chapters.upsertVariant(
            ChapterVariant(
                id = "variant-1",
                canonicalChapterId = "canonical-chapter-1",
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                mihonMangaId = firstManga,
                mihonChapterId = firstChapter,
                sourceChapterId = "/chapter/1",
                sourceChapterUrl = "/chapter/1",
                language = "en",
                rawName = "Source One Chapter",
                createdAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )
        chapters.upsertVariant(
            ChapterVariant(
                id = "variant-2",
                canonicalChapterId = "canonical-chapter-1",
                sourceMappingId = "mapping-2",
                sourceId = 2L,
                mihonMangaId = secondManga,
                mihonChapterId = secondChapter,
                sourceChapterId = "/chapter/1",
                sourceChapterUrl = "/chapter/1",
                language = "en",
                rawName = "Source Two Chapter",
                createdAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )

        database.tsuzuki_chapter_historyQueries.upsertTsuzukiChapterHistory(
            canonicalChapterId = "canonical-chapter-1",
            variantId = "variant-1",
            readAt = 3_000L,
            sessionReadDuration = 45L,
        )
    }

    private suspend fun insertManga(sourceId: Long, url: String, title: String): Long =
        database.mangasQueries.insertReturningId(
            source = sourceId,
            url = url,
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = title,
            status = 0L,
            thumbnailUrl = null,
            favorite = false,
            lastUpdate = null,
            nextUpdate = null,
            initialized = true,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            dateAdded = 100L,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            calculateInterval = 0L,
            version = 0L,
            notes = "",
            memo = buildJsonObject {},
        ).awaitAsOne()

    private suspend fun insertChapter(mangaId: Long, url: String, name: String): Long =
        database.chaptersQueries.insertReturningId(
            mangaId = mangaId,
            url = url,
            name = name,
            scanlator = null,
            read = false,
            bookmark = false,
            lastPageRead = 0L,
            chapterNumber = 1.0,
            sourceOrder = 0L,
            dateFetch = 2_000L,
            dateUpload = 2_000L,
            version = 0L,
            memo = buildJsonObject {},
        ).awaitAsOne()

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch")}")
    }

    private data class HistoryRow(
        val mangaId: Long,
        val chapterId: Long,
        val title: String,
        val sourceId: Long,
        val readAt: Long?,
        val readDuration: Long,
    )
}
