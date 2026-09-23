package tachiyomi.data.tsuzuki

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
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
import tachiyomi.data.tsuzuki.sync.SyncOutboxRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverride
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverrideKind
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import java.nio.file.Files

class ChapterOverrideRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: ChapterOverrideRepositoryImpl
    private lateinit var readerPreferences: CanonicalReaderPreferenceRepositoryImpl
    private lateinit var outbox: SyncOutboxRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-chapter-override-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ChapterOverrideRepositoryImplTest::class.java
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
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Title",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository = ChapterOverrideRepositoryImpl(database)
        readerPreferences = CanonicalReaderPreferenceRepositoryImpl(database)
        outbox = SyncOutboxRepositoryImpl(database)
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
    fun `chapter overrides are portable persisted and enqueue sync`() = runBlocking<Unit> {
        val chapterOverride = ChapterOverride(
            id = "override-1",
            canonicalTitleId = "title-1",
            canonicalChapterKey = "01|0000000073|9999999999|00|",
            sourceId = 42L,
            sourceTitleUrl = "/manga/title",
            sourceChapterId = "chapter-73",
            kind = ChapterOverrideKind.SOURCE_CHAPTER_MAPPING,
            payloadJson = """{"canonicalChapter":"73"}""",
            createdAt = 10L,
            updatedAt = 20L,
        )

        repository.upsert(chapterOverride)

        repository.getById("override-1") shouldBe chapterOverride
        repository.getAll() shouldContainExactly listOf(chapterOverride)
        outbox.get(SyncDocumentKind.CHAPTER_OVERRIDES)?.documentKind shouldBe
            SyncDocumentKind.CHAPTER_OVERRIDES

        repository.upsert(
            chapterOverride.copy(
                revision = 1L,
                updatedAt = 30L,
                deletedAt = 30L,
            ),
        )

        repository.getAll() shouldBe emptyList()
        repository.getAll(includeDeleted = true).single().deletedAt shouldBe 30L
    }

    @Test
    fun `reader fallback preference shares chapter override outbox`() = runBlocking<Unit> {
        readerPreferences.upsert(
            CanonicalReaderPreference(
                canonicalTitleId = "title-1",
                automaticFallback = true,
                updatedAt = 50L,
            ),
        )

        readerPreferences.getAll().map { it.canonicalTitleId } shouldContainExactly listOf("title-1")
        outbox.get(SyncDocumentKind.CHAPTER_OVERRIDES)?.documentKind shouldBe
            SyncDocumentKind.CHAPTER_OVERRIDES

        outbox.clear(SyncDocumentKind.CHAPTER_OVERRIDES)
        readerPreferences.delete("title-1")

        readerPreferences.get("title-1") shouldBe null
        outbox.get(SyncDocumentKind.CHAPTER_OVERRIDES)?.documentKind shouldBe
            SyncDocumentKind.CHAPTER_OVERRIDES
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
