package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
import java.nio.file.Files

class CanonicalChapterRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: CanonicalChapterRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalChapterRepositoryImplTest::class.java
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
        seedTitleAndMapping()
        repository = CanonicalChapterRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) {
            driver.close()
        }
        if (originalNativeLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalNativeLibraryPath)
        }
        nativeLibraryDirectory?.toFile()?.deleteRecursively()
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    @Test
    fun `upsert get list and observe chapters by title`() = runBlocking<Unit> {
        val first = chapter("chapter-1", "Chapter 1")
        val second = chapter("chapter-2", "Chapter 2", baseNumber = 2)

        repository.upsert(first)
        repository.upsert(second)
        repository.upsert(first.copy(updatedAt = 200L, title = "Updated"))
        val updatedFirst = first.copy(updatedAt = 200L, title = "Updated")

        repository.getByCanonicalTitleId("title-1") shouldContainExactly listOf(updatedFirst, second)
        repository.getById(first.id) shouldBe updatedFirst
        repository.observeByCanonicalTitleId("title-1").first() shouldContainExactly listOf(updatedFirst, second)
    }

    @Test
    fun `variant upsert is idempotent and preserves lossless metadata`() = runBlocking<Unit> {
        val first = variant(id = "variant-1", sourceChapterId = "source-1")
        val second = variant(
            id = "variant-2",
            sourceId = 8L,
            sourceChapterId = "source-2",
            sourceMappingId = "mapping-2",
        )
        repository.upsert(chapter("chapter-1"))
        repository.upsertVariant(first)
        repository.upsertVariant(first.copy(updatedAt = 200L, rawName = "Chapter 1 revised"))
        repository.upsertVariant(second)

        repository.getVariantBySourceIdentity(7L, "source-1") shouldBe first.copy(
            updatedAt = 200L,
            rawName = "Chapter 1 revised",
        )
        repository.getVariantsByCanonicalChapterId("chapter-1") shouldContainExactly listOf(
            first.copy(updatedAt = 200L, rawName = "Chapter 1 revised"),
            second,
        )
        repository.getVariantsBySourceMappingId("mapping-1").size shouldBe 1
        repository.getVariantsBySourceMappingId("mapping-2") shouldContainExactly listOf(second)

        driver.execute(null, "DELETE FROM tsuzuki_source_mappings WHERE id = ?", 1) {
            bindString(0, "mapping-1")
        }.await()

        repository.getVariantBySourceIdentity(7L, "source-1") shouldBe null
        repository.getVariantBySourceIdentity(8L, "source-2") shouldBe second
    }

    @Test
    fun `source identity is unique and a refresh keeps its original variant id`() = runBlocking<Unit> {
        repository.upsert(chapter("chapter-1"))
        repository.upsertVariant(variant(id = "variant-1", sourceChapterId = "source-1"))
        repository.upsertVariant(variant(id = "new-id", sourceChapterId = "source-1", rawName = "Updated"))

        val stored = repository.getVariantBySourceIdentity(7L, "source-1")!!
        stored.id shouldBe "variant-1"
        stored.rawName shouldBe "Updated"
        repository.getVariantsByCanonicalChapterId("chapter-1").size shouldBe 1
    }

    @Test
    fun `batch writes commit and rollback atomically`() = runBlocking<Unit> {
        repository.upsertBatch(
            chapters = listOf(chapter("chapter-committed")),
            variants = listOf(
                variant(
                    id = "variant-committed",
                    canonicalChapterId = "chapter-committed",
                    sourceChapterId = "source-committed",
                ),
            ),
        )
        repository.getById("chapter-committed") shouldBe chapter("chapter-committed")

        shouldThrow<Throwable> {
            repository.upsertBatch(
                chapters = listOf(chapter("chapter-rolled-back")),
                variants = listOf(
                    variant(
                        id = "variant-rolled-back",
                        canonicalChapterId = "chapter-rolled-back",
                        sourceChapterId = "source-rolled-back",
                        sourceMappingId = "missing-mapping",
                    ),
                ),
            )
        }
        repository.getById("chapter-rolled-back") shouldBe null
        repository.getVariantBySourceIdentity(7L, "source-rolled-back") shouldBe null
    }

    @Test
    fun `title and mapping foreign keys cascade without touching legacy manga rows`() = runBlocking<Unit> {
        val mangaId = database.mangasQueries.insertReturningId(
            source = 99L,
            url = "/legacy",
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = "Legacy",
            status = 0L,
            thumbnailUrl = null,
            favorite = false,
            lastUpdate = null,
            nextUpdate = null,
            initialized = false,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            dateAdded = 0L,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            calculateInterval = 0L,
            version = 0L,
            notes = "",
            memo = buildJsonObject {},
        ).awaitAsOne()
        val chapterId = database.chaptersQueries.insertReturningId(
            mangaId = mangaId,
            url = "/legacy/chapter",
            name = "Legacy Chapter",
            scanlator = null,
            read = false,
            bookmark = false,
            lastPageRead = 0L,
            chapterNumber = 1.0,
            sourceOrder = 0L,
            dateFetch = 0L,
            dateUpload = 0L,
            version = 0L,
            memo = buildJsonObject {},
        ).awaitAsOne()
        repository.upsert(chapter("chapter-cascade"))
        repository.upsertVariant(
            variant(
                id = "variant-cascade",
                canonicalChapterId = "chapter-cascade",
                sourceChapterId = "source-cascade",
            ),
        )

        driver.execute(null, "DELETE FROM tsuzuki_titles WHERE id = ?", 1) {
            bindString(0, "title-1")
        }.await()

        repository.getById("chapter-cascade") shouldBe null
        repository.getVariantBySourceIdentity(7L, "source-cascade") shouldBe null
        database.mangasQueries.getMangaById(mangaId).awaitAsOneOrNull()!!.title shouldBe "Legacy"
        database.chaptersQueries.getChapterById(chapterId).awaitAsOneOrNull()!!.name shouldBe "Legacy Chapter"
    }

    private suspend fun seedTitleAndMapping() {
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
            verifiedByUser = false,
            availability = "AVAILABLE",
            preferredOverride = false,
            createdAt = 100L,
            updatedAt = 100L,
        )
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = "mapping-2",
            canonicalTitleId = "title-1",
            mihonMangaId = null,
            sourceId = 8L,
            sourceUrl = "/title-2",
            language = "pt-BR",
            matchConfidence = null,
            verifiedByUser = false,
            availability = "AVAILABLE",
            preferredOverride = false,
            createdAt = 100L,
            updatedAt = 100L,
        )
    }

    private fun chapter(
        id: String,
        displayNumber: String = "1",
        baseNumber: Int? = 1,
    ) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = displayNumber,
        type = CanonicalChapterType.REGULAR,
        baseNumber = baseNumber,
        confidence = 1.0,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun variant(
        id: String,
        canonicalChapterId: String = "chapter-1",
        sourceId: Long = 7L,
        sourceChapterId: String,
        sourceMappingId: String = "mapping-1",
        rawName: String = "Chapter 1",
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = canonicalChapterId,
        sourceMappingId = sourceMappingId,
        sourceId = sourceId,
        mihonMangaId = null,
        mihonChapterId = null,
        sourceChapterId = sourceChapterId,
        sourceChapterUrl = "/chapter/$sourceChapterId",
        language = "en",
        scanlationGroup = "Group",
        version = 1L,
        releaseDate = 100L,
        rawName = rawName,
        rawNumberHint = 1.0,
        rawSourceOrder = 0L,
        rawSourceMetadata = buildJsonObject {
            put("release", JsonPrimitive("raw"))
            put("sourceNumber", JsonPrimitive(1))
        },
        createdAt = 100L,
        updatedAt = 100L,
    )
}
