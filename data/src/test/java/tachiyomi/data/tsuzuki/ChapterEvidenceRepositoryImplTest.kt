package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
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
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceWrite
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import java.nio.file.Files

class ChapterEvidenceRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var evidenceRepository: ChapterEvidenceRepositoryImpl
    private lateinit var chapterRepository: CanonicalChapterRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ChapterEvidenceRepositoryImplTest::class.java
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
        evidenceRepository = ChapterEvidenceRepositoryImpl(database)
        chapterRepository = CanonicalChapterRepositoryImpl(database)
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
    fun `batch refresh preserves evidence id metadata and remains idempotent`() = runBlocking<Unit> {
        chapterRepository.upsert(chapter("chapter-1", 1))
        val initial = addonEvidence(id = "stable-id", label = "Chapter 1", key = "source-1")
        evidenceRepository.upsert(initial, "chapter-1")
        driver.execute(
            null,
            "UPDATE tsuzuki_chapter_evidence SET raw_metadata = X'010203' WHERE id = 'stable-id'",
            0,
        ).await()

        val refresh = ChapterEvidenceWrite(
            evidence = initial.copy(id = "new-observation-id", rawLabel = "Chapter 1 revised", observedAt = 2L),
            mappedCanonicalChapterId = "chapter-1",
        )
        val firstRefresh = evidenceRepository.upsertBatch(listOf(refresh)).single()
        val secondRefresh = evidenceRepository.upsertBatch(listOf(refresh.copy(evidence = refresh.evidence.copy(id = "third-id"))))
            .single()

        firstRefresh.evidence.id shouldBe "stable-id"
        secondRefresh.evidence.id shouldBe "stable-id"
        firstRefresh.evidence.rawLabel shouldBe "Chapter 1 revised"
        secondRefresh.rawMetadata.toList() shouldBe listOf(1.toByte(), 2.toByte(), 3.toByte())
        evidenceRepository.getByCanonicalTitleId("title-1") shouldHaveSize 1
    }

    @Test
    fun `reconciliation rolls back chapter and evidence writes together after injected evidence failure`() = runBlocking<Unit> {
        val failingEvidenceRepository = object : ChapterEvidenceRepository by evidenceRepository {
            override suspend fun upsertBatch(writes: List<ChapterEvidenceWrite>) =
                evidenceRepository.upsertBatch(writes.take(1)).also {
                    error("Injected failure after first evidence write")
                }
        }
        val reconciler = ReconcileChapterEvidence(
            ParseCanonicalChapterLabel(),
            chapterRepository,
            failingEvidenceRepository,
        )

        shouldThrow<IllegalStateException> {
            runBlocking {
                reconciler.execute(
                    "title-1",
                    listOf(
                        editorialEvidence(id = "first", label = "Chapter 1", key = "source-1"),
                        editorialEvidence(id = "second", label = "Chapter 2", key = "source-2"),
                    ),
                )
            }
        }

        chapterRepository.getByCanonicalTitleId("title-1") shouldHaveSize 0
        evidenceRepository.getByCanonicalTitleId("title-1") shouldHaveSize 0
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    private fun chapter(id: String, number: Int) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = number.toString(),
        volume = null,
        title = null,
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        part = null,
        alphaSuffix = null,
        confidence = 1.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun addonEvidence(id: String, label: String, key: String) = ChapterEvidence(
        id = id,
        canonicalTitleId = "title-1",
        producerKind = ProducerKind.ADDON,
        producerId = "addon",
        externalChapterKey = key,
        rawLabel = label,
        rawNumber = null,
        volume = null,
        title = null,
        observedAt = 1L,
        confidence = 1.0,
        authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
    )

    private fun editorialEvidence(id: String, label: String, key: String) =
        addonEvidence(id, label, key).copy(
            producerKind = ProducerKind.INTEGRATION,
            producerId = "integration",
            authority = ChapterEvidenceAuthority.EDITORIAL,
        )
}
