package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
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
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import java.nio.file.Files

class CanonicalReadingRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var chapterRepository: CanonicalChapterRepositoryImpl
    private lateinit var repository: CanonicalReadingRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-reading-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalReadingRepositoryImplTest::class.java
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

        seedTitleMappingAndChapters()
        chapterRepository = CanonicalChapterRepositoryImpl(database)
        repository = CanonicalReadingRepositoryImpl(database)
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
    fun `progress is canonical and observable independent of source variants`() = runBlocking<Unit> {
        val first = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = false,
            lastPageRead = 4L,
            updatedAt = 100L,
        )
        repository.upsertProgress(first)

        repository.getProgress("chapter-1") shouldBe first
        repository.observeProgress("chapter-1").first() shouldBe first

        val completed = first.copy(read = true, lastPageRead = 20L, updatedAt = 200L)
        repository.upsertProgress(completed)

        repository.getProgress("chapter-1") shouldBe completed
        repository.getProgressByCanonicalTitleId("title-1") shouldContainExactly listOf(completed)
    }

    @Test
    fun `history accumulates duration and remembers the last used variant`() = runBlocking<Unit> {
        repository.recordHistory(
            CanonicalChapterHistoryUpdate(
                canonicalChapterId = "chapter-1",
                variantId = "variant-1",
                readAt = 100L,
                sessionReadDuration = 30L,
            ),
        )
        repository.recordHistory(
            CanonicalChapterHistoryUpdate(
                canonicalChapterId = "chapter-1",
                variantId = "variant-2",
                readAt = 200L,
                sessionReadDuration = 20L,
            ),
        )

        repository.getHistory("chapter-1")!!.let { history ->
            history.canonicalChapterId shouldBe "chapter-1"
            history.lastVariantId shouldBe "variant-2"
            history.lastReadAt shouldBe 200L
            history.totalReadDuration shouldBe 50L
        }
    }

    @Test
    fun `reader checkpoint persists progress and history atomically`() = runBlocking<Unit> {
        repository.recordCheckpoint(
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                read = true,
                lastPageRead = 10L,
                updatedAt = 300L,
            ),
            history = CanonicalChapterHistoryUpdate(
                canonicalChapterId = "chapter-1",
                variantId = "variant-1",
                readAt = 300L,
                sessionReadDuration = 40L,
            ),
        )

        repository.getProgress("chapter-1")?.read shouldBe true
        repository.getHistory("chapter-1")?.totalReadDuration shouldBe 40L

        shouldThrow<IllegalArgumentException> {
            repository.recordCheckpoint(
                progress = CanonicalChapterProgress(
                    canonicalChapterId = "chapter-1",
                    updatedAt = 400L,
                ),
                history = CanonicalChapterHistoryUpdate(
                    canonicalChapterId = "chapter-2",
                    readAt = 400L,
                    sessionReadDuration = 1L,
                ),
            )
        }
        repository.getProgress("chapter-1")?.updatedAt shouldBe 300L
    }

    @Test
    fun `deleting a variant preserves canonical history and clears only variant context`() = runBlocking<Unit> {
        repository.recordHistory(
            CanonicalChapterHistoryUpdate(
                canonicalChapterId = "chapter-1",
                variantId = "variant-1",
                readAt = 100L,
                sessionReadDuration = 30L,
            ),
        )

        driver.execute(null, "DELETE FROM tsuzuki_chapter_variants WHERE id = ?", 1) {
            bindString(0, "variant-1")
        }.await()

        repository.getHistory("chapter-1")!!.let { history ->
            history.lastVariantId shouldBe null
            history.lastReadAt shouldBe 100L
            history.totalReadDuration shouldBe 30L
        }
    }

    @Test
    fun `deleting canonical chapter cascades progress and history only`() = runBlocking<Unit> {
        repository.recordCheckpoint(
            progress = CanonicalChapterProgress(
                canonicalChapterId = "chapter-1",
                read = true,
                updatedAt = 100L,
            ),
            history = CanonicalChapterHistoryUpdate(
                canonicalChapterId = "chapter-1",
                variantId = "variant-1",
                readAt = 100L,
                sessionReadDuration = 10L,
            ),
        )

        driver.execute(null, "DELETE FROM tsuzuki_canonical_chapters WHERE id = ?", 1) {
            bindString(0, "chapter-1")
        }.await()

        repository.getProgress("chapter-1") shouldBe null
        repository.getHistory("chapter-1") shouldBe null
        chapterRepository.getById("chapter-2")!!.id shouldBe "chapter-2"
    }

    private suspend fun seedTitleMappingAndChapters() {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Title",
            identityState = "SOURCE_ONLY",
            createdAt = 100L,
            updatedAt = 100L,
        )
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-1",
            canonicalTitleId = "title-1",
            mihonMangaId = null,
            sourceId = 7L,
            sourceUrl = "/title",
            language = "en",
            matchConfidence = null,
            verifiedByUser = true,
            availability = "AVAILABLE",
            preferredOverride = true,
            createdAt = 100L,
            updatedAt = 100L,
        )

        val canonicalRepository = CanonicalChapterRepositoryImpl(database)
        canonicalRepository.upsert(chapter("chapter-1", 1))
        canonicalRepository.upsert(chapter("chapter-2", 2))
        canonicalRepository.upsertVariant(variant("variant-1", "chapter-1", "/chapter/1"))
        canonicalRepository.upsertVariant(variant("variant-2", "chapter-1", "/chapter/1-alt", sourceId = 8L))
    }

    private fun chapter(id: String, number: Int) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = number.toString(),
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        confidence = 1.0,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun variant(
        id: String,
        canonicalChapterId: String,
        url: String,
        sourceId: Long = 7L,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = canonicalChapterId,
        sourceMappingId = "mapping-1",
        sourceId = sourceId,
        sourceChapterId = url,
        sourceChapterUrl = url,
        language = "en",
        rawName = "Chapter",
        rawSourceMetadata = buildJsonObject {},
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
