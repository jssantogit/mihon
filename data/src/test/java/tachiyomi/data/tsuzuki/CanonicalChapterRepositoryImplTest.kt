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
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.LegacyInventoryEvidenceAdapter
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileLegacyChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
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
    fun `safe exact duplicate consolidation rekeys evidence and variants before deleting duplicate`() =
        runBlocking<Unit> {
            val preferred = chapter("chapter-preferred")
            val duplicate = chapter("chapter-duplicate")
            repository.upsert(preferred)
            repository.upsert(duplicate)
            repository.upsertVariant(
                variant(
                    id = "variant-preferred",
                    canonicalChapterId = preferred.id,
                    sourceId = 7L,
                    sourceChapterId = "/preferred",
                    sourceMappingId = "mapping-1",
                ),
            )
            repository.upsertVariant(
                variant(
                    id = "variant-duplicate",
                    canonicalChapterId = duplicate.id,
                    sourceId = 8L,
                    sourceChapterId = "/duplicate",
                    sourceMappingId = "mapping-2",
                ),
            )
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            evidenceRepository.upsert(
                ChapterEvidence(
                    id = "evidence-preferred",
                    canonicalTitleId = "title-1",
                    producerKind = ProducerKind.ADDON,
                    producerId = "source-7",
                    externalChapterKey = "7:/preferred",
                    rawLabel = "Chapter 1",
                    rawNumber = 1.0,
                    volume = null,
                    title = null,
                    observedAt = 100L,
                    confidence = 1.0,
                    authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                ),
                preferred.id,
            )
            evidenceRepository.upsert(
                ChapterEvidence(
                    id = "evidence-duplicate",
                    canonicalTitleId = "title-1",
                    producerKind = ProducerKind.ADDON,
                    producerId = "source-8",
                    externalChapterKey = "8:/duplicate",
                    rawLabel = "Chapter 1",
                    rawNumber = 1.0,
                    volume = null,
                    title = null,
                    observedAt = 100L,
                    confidence = 1.0,
                    authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                ),
                duplicate.id,
            )

            repository.consolidateExactDuplicateIfSafe(
                canonicalTitleId = "title-1",
                preferredChapterId = preferred.id,
                duplicateChapterId = duplicate.id,
            ) shouldBe true

            repository.getByCanonicalTitleId("title-1").map { it.id } shouldBe listOf(preferred.id)
            repository.getVariantBySourceIdentity(7L, "/preferred")?.canonicalChapterId shouldBe preferred.id
            repository.getVariantBySourceIdentity(8L, "/duplicate")?.canonicalChapterId shouldBe preferred.id
            evidenceRepository.getByCanonicalTitleId("title-1")
                .map { it.mappedCanonicalChapterId }
                .toSet() shouldBe setOf(preferred.id)
        }

    @Test
    fun `exact duplicate consolidation refuses to delete chapter with reading progress`() = runBlocking<Unit> {
        val preferred = chapter("chapter-preferred")
        val duplicate = chapter("chapter-duplicate")
        repository.upsert(preferred)
        repository.upsert(duplicate)
        database.tsuzuki_chapter_progressQueries.upsertTsuzukiChapterProgress(
            canonicalChapterId = duplicate.id,
            read = false,
            lastPageRead = 3L,
            lastVariantId = null,
            updatedAt = 200L,
        )

        repository.consolidateExactDuplicateIfSafe(
            canonicalTitleId = "title-1",
            preferredChapterId = preferred.id,
            duplicateChapterId = duplicate.id,
        ) shouldBe false

        repository.getByCanonicalTitleId("title-1").map { it.id }.toSet() shouldBe
            setOf(preferred.id, duplicate.id)
        database.tsuzuki_chapter_progressQueries.getTsuzukiChapterProgress(duplicate.id)
            .awaitAsOneOrNull() shouldBe
            tachiyomi.data.Tsuzuki_chapter_progress(
                canonical_chapter_id = duplicate.id,
                read = false,
                last_page_read = 3L,
                last_variant_id = null,
                updated_at = 200L,
            )
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

    @Test
    fun `atomic legacy projection preserves both language variants and rehomes a reused volume URL`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val reconciler = ReconcileChapterEvidence(
                ParseCanonicalChapterLabel(),
                repository,
                evidenceRepository,
            )
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                reconciler,
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            repository.upsert(chapter("chapter-volume-1", "4", baseNumber = 4).copy(volume = 1))
            repository.upsert(chapter("chapter-volume-2", "4", baseNumber = 4).copy(volume = 2))
            val en = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
            val pt = legacyInventory(8L, "mapping-2", "pt-BR", "Vol. 1 Ch. 4")

            projection.execute(listOf(en, pt), observedAt = 100L).size shouldBe 2
            val englishBefore = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val portugueseBefore = requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/4"))
            englishBefore.canonicalChapterId shouldBe "chapter-volume-1"
            portugueseBefore.canonicalChapterId shouldBe "chapter-volume-1"
            englishBefore.language shouldBe "en"
            portugueseBefore.language shouldBe "pt-BR"

            projection.execute(listOf(pt, en), observedAt = 200L).size shouldBe 2
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.id shouldBe englishBefore.id
            repository.getVariantBySourceIdentity(8L, "/chapter/4")?.id shouldBe portugueseBefore.id

            projection.execute(
                listOf(en.copy(chapters = en.chapters.map { it.copy(rawName = "Vol. 2 Ch. 4") })),
                observedAt = 300L,
            ).size shouldBe 1
            val englishAfter = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val portugueseAfter = requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/4"))
            val volumeTwo = repository.getByCanonicalTitleId("title-1").single { it.volume == 2 }
            englishAfter.id shouldBe englishBefore.id
            englishAfter.canonicalChapterId shouldBe volumeTwo.id
            portugueseAfter.id shouldBe portugueseBefore.id
            portugueseAfter.canonicalChapterId shouldBe "chapter-volume-1"
            evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "mihon-legacy:title-1:7",
                "7:/chapter/4",
            )?.mappedCanonicalChapterId shouldBe volumeTwo.id
            evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "mihon-legacy:title-1:8",
                "8:/chapter/4",
            )?.mappedCanonicalChapterId shouldBe "chapter-volume-1"
            repository.getByCanonicalTitleId("title-1").size shouldBe 2
        }

    @Test
    fun `failed second variant write rolls back canonical chapters evidence and first variant before retry`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            repository.upsert(chapter("chapter-volume-1", "4", baseNumber = 4).copy(volume = 1))
            val original = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
            projection.execute(listOf(original), observedAt = 100L)
            val oldVariant = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val before = evidenceRepository.getByCanonicalTitleId("title-1")
            val good = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 5", "/chapter/5")
            val bad = legacyInventory(8L, "mapping-2", "pt-BR", "Vol. 2 Ch. 6", "/chapter/6")
            // Fail after the first variant INSERT, not during source validation,
            // so the database proves the whole projection transaction rolls back.
            driver.execute(
                null,
                "CREATE TRIGGER reject_second_variant BEFORE INSERT ON tsuzuki_chapter_variants " +
                    "WHEN NEW.source_chapter_id = '/chapter/6' " +
                    "BEGIN SELECT RAISE(ABORT, 'injected second-variant failure'); END",
                0,
            ).await()

            shouldThrow<Throwable> {
                projection.execute(listOf(good, bad), observedAt = 200L)
            }
            repository.getByCanonicalTitleId("title-1").map { it.id } shouldBe listOf("chapter-volume-1")
            evidenceRepository.getByCanonicalTitleId("title-1").map {
                it.evidence to it.mappedCanonicalChapterId
            } shouldBe before.map { it.evidence to it.mappedCanonicalChapterId }
            repository.getVariantBySourceIdentity(7L, "/chapter/4") shouldBe oldVariant
            repository.getVariantBySourceIdentity(7L, "/chapter/5") shouldBe null
            repository.getVariantBySourceIdentity(8L, "/chapter/6") shouldBe null

            driver.execute(null, "DROP TRIGGER reject_second_variant", 0).await()
            projection.execute(listOf(good, bad), observedAt = 300L).size shouldBe 2
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.id shouldBe oldVariant.id
            repository.getVariantBySourceIdentity(7L, "/chapter/5")?.canonicalChapterId shouldBe
                repository.getByCanonicalTitleId("title-1").single { it.baseNumber == 5 }.id
            repository.getVariantBySourceIdentity(8L, "/chapter/6")?.canonicalChapterId shouldBe
                repository.getByCanonicalTitleId("title-1").single { it.baseNumber == 6 }.id
            evidenceRepository.getByCanonicalTitleId("title-1").size shouldBe 3
        }

    @Test
    fun `unreliable legacy observations from separate mappings stay unmapped and never publish variants`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val english = legacyInventory(7L, "mapping-1", "en", "An unknown release", "/unknown")
            val portuguese = legacyInventory(8L, "mapping-2", "pt-BR", "An unknown release", "/unknown")
            projection.execute(listOf(english, portuguese), observedAt = 100L) shouldBe emptyList()
            repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            repository.getVariantBySourceIdentity(7L, "/unknown") shouldBe null
            repository.getVariantBySourceIdentity(8L, "/unknown") shouldBe null
            evidenceRepository.getByCanonicalTitleId("title-1").size shouldBe 2
            listOf(7L, 8L).forEach { sourceId ->
                evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "mihon-legacy:title-1:$sourceId",
                    "$sourceId:/unknown",
                )?.mappedCanonicalChapterId shouldBe null
            }
        }

    @Test
    fun `newer unmapped add-on evidence blocks stale legacy inventory but allows a later fetch`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val newerEvidence = ChapterEvidence(
                id = "detail-evidence",
                canonicalTitleId = "title-1",
                producerKind = ProducerKind.ADDON,
                producerId = "detail-addon",
                externalChapterKey = "7:/chapter/4",
                rawLabel = "Chapter 2",
                rawNumber = 2.0,
                volume = null,
                title = null,
                observedAt = 200L,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            )
            evidenceRepository.upsert(newerEvidence, mappedCanonicalChapterId = null)

            val stale = legacyInventory(7L, "mapping-1", "en", "Chapter 1").copy(
                fetchStartedAtMillis = 100L,
            )
            projection.execute(listOf(stale), observedAt = 300L) shouldBe emptyList()
            repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            repository.getVariantBySourceIdentity(7L, "/chapter/4") shouldBe null
            evidenceRepository.getByCanonicalTitleId("title-1").map { it.evidence.id } shouldBe
                listOf(newerEvidence.id)

            projection.execute(
                listOf(stale.copy(fetchStartedAtMillis = 201L)),
                observedAt = 400L,
            ).size shouldBe 1

            val canonical = repository.getByCanonicalTitleId("title-1").single()
            canonical.baseNumber shouldBe 1
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.canonicalChapterId shouldBe canonical.id
            evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "detail-addon",
                "7:/chapter/4",
            )?.mappedCanonicalChapterId shouldBe null
            evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "mihon-legacy:title-1:7",
                "7:/chapter/4",
            )?.mappedCanonicalChapterId shouldBe canonical.id
        }

    @Test
    fun `staged projection rejects a mapping owned by another title without writing any chapter state`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            database.tsuzuki_titlesQueries.insertTsuzukiTitle(
                id = "foreign-title",
                displayTitle = "Unrelated title",
                identityState = "SOURCE_ONLY",
                createdAt = 100L,
                updatedAt = 100L,
            )
            database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
                id = "foreign-mapping",
                canonicalTitleId = "foreign-title",
                mihonMangaId = 13L,
                sourceId = 9L,
                sourceUrl = "/foreign",
                language = "en",
                matchConfidence = null,
                verifiedByUser = false,
                availability = "AVAILABLE",
                preferredOverride = false,
                createdAt = 100L,
                updatedAt = 100L,
            )

            val foreign = legacyInventory(9L, "foreign-mapping", "en", "Vol. 1 Ch. 4")
            shouldThrow<IllegalArgumentException> {
                projection.execute(listOf(foreign), observedAt = 200L)
            }
            repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            evidenceRepository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            repository.getVariantsBySourceMappingId("foreign-mapping") shouldBe emptyList()
        }

    @Test
    fun `staged projection rejects a stale source title URL despite matching manga and source IDs`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val stale = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
                .copy(sourceUrl = "/stale-title")
            shouldThrow<IllegalArgumentException> {
                projection.execute(listOf(stale), observedAt = 200L)
            }
            repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            evidenceRepository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            repository.getVariantsBySourceMappingId("mapping-1") shouldBe emptyList()
        }

    @Test
    fun `empty inventories still validate mapping ownership before reporting success`() = runBlocking<Unit> {
        val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
        val projection = ReconcileLegacyChapterEvidence(
            LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
            ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
            repository,
            SourceTitleMappingRepositoryImpl(database),
        )
        val invalid = legacyInventory(7L, "missing-mapping", "en", "Chapter 4")
            .copy(sourceUrl = "/title", chapters = emptyList())
        shouldThrow<IllegalArgumentException> {
            projection.execute(listOf(invalid), observedAt = 200L)
        }
        projection.execute(
            listOf(
                legacyInventory(7L, "mapping-1", "en", "Chapter 4").copy(
                    sourceUrl = "/title",
                    chapters = emptyList(),
                ),
            ),
            observedAt = 300L,
        ) shouldBe emptyList()
        evidenceRepository.getByCanonicalTitleId("title-1") shouldBe emptyList()
    }

    @Test
    fun `empty legacy inventories preserve canonical evidence and operational variants`() = runBlocking<Unit> {
        val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
        val projection = ReconcileLegacyChapterEvidence(
            LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
            ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
            repository,
            SourceTitleMappingRepositoryImpl(database),
        )
        val english = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
        val portuguese = legacyInventory(8L, "mapping-2", "pt-BR", "Vol. 1 Ch. 4")
        projection.execute(listOf(english, portuguese), observedAt = 100L).size shouldBe 2
        val canonicalId = repository.getByCanonicalTitleId("title-1").single().id
        val initialVariantIds = mapOf(
            7L to requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4")).id,
            8L to requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/4")).id,
        )

        projection.execute(
            listOf(
                english.copy(chapters = emptyList(), fetchStartedAtMillis = 200L),
                portuguese,
            ),
            observedAt = 300L,
        ).size shouldBe 1
        repository.getByCanonicalTitleId("title-1").map { it.id } shouldBe listOf(canonicalId)
        listOf(7L, 8L).forEach { sourceId ->
            repository.getVariantBySourceIdentity(sourceId, "/chapter/4")?.let { variant ->
                variant.id shouldBe initialVariantIds.getValue(sourceId)
                variant.canonicalChapterId shouldBe canonicalId
            }
        }
        evidenceRepository.getByCanonicalTitleId("title-1").map { it.mappedCanonicalChapterId }
            .toSet() shouldBe setOf(canonicalId)

        val chaptersBeforeEmpty = repository.getByCanonicalTitleId("title-1")
        val evidenceBeforeEmpty = evidenceRepository.getByCanonicalTitleId("title-1")
        val variantsBeforeEmpty = listOf(7L, 8L).associateWith { sourceId ->
            repository.getVariantBySourceIdentity(sourceId, "/chapter/4")
        }
        projection.execute(
            listOf(
                english.copy(chapters = emptyList(), fetchStartedAtMillis = 400L),
                portuguese.copy(chapters = emptyList(), fetchStartedAtMillis = 400L),
            ),
            observedAt = 500L,
        ) shouldBe emptyList()

        repository.getByCanonicalTitleId("title-1") shouldBe chaptersBeforeEmpty
        val evidenceAfterEmpty = evidenceRepository.getByCanonicalTitleId("title-1").associateBy {
            it.evidence.id
        }
        evidenceAfterEmpty.keys shouldBe evidenceBeforeEmpty.map { it.evidence.id }.toSet()
        evidenceBeforeEmpty.forEach { before ->
            val after = requireNotNull(evidenceAfterEmpty[before.evidence.id])
            after.evidence shouldBe before.evidence
            after.mappedCanonicalChapterId shouldBe before.mappedCanonicalChapterId
            after.rawMetadata.contentEquals(before.rawMetadata) shouldBe true
        }
        listOf(7L, 8L).forEach { sourceId ->
            repository.getVariantBySourceIdentity(sourceId, "/chapter/4") shouldBe
                variantsBeforeEmpty.getValue(sourceId)
        }
    }

    @Test
    fun `staged projection reuses sole explicit volume candidate for reliable unqualified source`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val volumeOne = chapter("chapter-volume-1").copy(volume = 1)
            repository.upsert(volumeOne)
            val explicit = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 1", rawNumberHint = 1.0)
            val unqualified = legacyInventory(
                8L,
                "mapping-2",
                "pt-BR",
                "Chapter 1",
                "/chapter/plain",
                rawNumberHint = 1.0,
            )

            projection.execute(listOf(explicit, unqualified), observedAt = 200L).size shouldBe 2

            val chapters = repository.getByCanonicalTitleId("title-1")
            chapters.map { it.id } shouldBe listOf(volumeOne.id)
            chapters.single().volume shouldBe 1
            chapters.single().baseNumber shouldBe 1
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.canonicalChapterId shouldBe volumeOne.id
            repository.getVariantBySourceIdentity(8L, "/chapter/plain")?.canonicalChapterId shouldBe volumeOne.id

            val explicitVariantBeforeReplay = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val unqualifiedVariantBeforeReplay =
                requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/plain"))
            projection.execute(listOf(unqualified, explicit), observedAt = 300L).size shouldBe 2

            repository.getByCanonicalTitleId("title-1").map { it.id } shouldBe listOf(volumeOne.id)
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.id shouldBe explicitVariantBeforeReplay.id
            repository.getVariantBySourceIdentity(8L, "/chapter/plain")?.id shouldBe unqualifiedVariantBeforeReplay.id
        }

    @Test
    fun `staged projection retains fractional chapters after a partial inventory omits one URL`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val inventory = legacyInventory(7L, "mapping-1", "en", "Chapter 0").copy(
                fetchStartedAtMillis = 100L,
                chapters = listOf(
                    SourceChapterSnapshot(
                        sourceId = 7L,
                        sourceMappingId = "mapping-1",
                        sourceChapterId = "/chapter/0",
                        rawName = "Chapter 0",
                        language = "en",
                        rawNumberHint = 0.0,
                    ),
                    SourceChapterSnapshot(
                        sourceId = 7L,
                        sourceMappingId = "mapping-1",
                        sourceChapterId = "/chapter/0.5",
                        rawName = "Chapter 0.5",
                        language = "en",
                        rawNumberHint = 0.5,
                    ),
                    SourceChapterSnapshot(
                        sourceId = 7L,
                        sourceMappingId = "mapping-1",
                        sourceChapterId = "/chapter/1",
                        rawName = "Chapter 1",
                        language = "en",
                        rawNumberHint = 1.0,
                    ),
                ),
            )

            projection.execute(listOf(inventory), observedAt = 200L).size shouldBe 3
            val chapterIds = repository.getByCanonicalTitleId("title-1").associate { it.displayNumber to it.id }
            chapterIds.keys shouldBe setOf("0", "0.5", "1")
            val variantIds = listOf("/chapter/0", "/chapter/0.5", "/chapter/1").associateWith { sourceKey ->
                requireNotNull(repository.getVariantBySourceIdentity(7L, sourceKey)).id
            }
            val omittedEvidence = requireNotNull(
                evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "mihon-legacy:title-1:7",
                    "7:/chapter/0.5",
                ),
            )

            val partialInventory = inventory.copy(
                fetchStartedAtMillis = 300L,
                chapters = listOf(inventory.chapters[0], inventory.chapters[2]),
            )
            projection.execute(listOf(partialInventory), observedAt = 400L).size shouldBe 2

            repository.getByCanonicalTitleId("title-1").associate { it.displayNumber to it.id } shouldBe chapterIds
            listOf("/chapter/0", "/chapter/0.5", "/chapter/1").forEach { sourceKey ->
                repository.getVariantBySourceIdentity(7L, sourceKey)?.id shouldBe variantIds.getValue(sourceKey)
            }
            val persistedOmittedEvidence = requireNotNull(
                evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "mihon-legacy:title-1:7",
                    "7:/chapter/0.5",
                ),
            )
            persistedOmittedEvidence.evidence shouldBe omittedEvidence.evidence
            persistedOmittedEvidence.mappedCanonicalChapterId shouldBe omittedEvidence.mappedCanonicalChapterId
            persistedOmittedEvidence.rawMetadata.contentEquals(omittedEvidence.rawMetadata) shouldBe true
        }

    @Test
    fun `partial legacy refresh preserves an omitted mapping variant and its evidence`() = runBlocking<Unit> {
        val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
        val projection = ReconcileLegacyChapterEvidence(
            LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
            ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
            repository,
            SourceTitleMappingRepositoryImpl(database),
        )
        val english = legacyInventory(7L, "mapping-1", "en", "Chapter 4")
        val portuguese = legacyInventory(8L, "mapping-2", "pt-BR", "Chapter 4")

        projection.execute(listOf(english, portuguese), observedAt = 100L).size shouldBe 2
        val canonical = repository.getByCanonicalTitleId("title-1").single()
        val omittedVariant = requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/4"))
        omittedVariant.canonicalChapterId shouldBe canonical.id
        val omittedEvidence = requireNotNull(
            evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "mihon-legacy:title-1:8",
                "8:/chapter/4",
            ),
        )

        projection.execute(listOf(english), observedAt = 200L).size shouldBe 1

        repository.getByCanonicalTitleId("title-1").map { it.id } shouldBe listOf(canonical.id)
        repository.getVariantBySourceIdentity(8L, "/chapter/4") shouldBe omittedVariant
        val persistedOmittedEvidence = requireNotNull(
            evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "mihon-legacy:title-1:8",
                "8:/chapter/4",
            ),
        )
        persistedOmittedEvidence.evidence shouldBe omittedEvidence.evidence
        persistedOmittedEvidence.mappedCanonicalChapterId shouldBe canonical.id
        persistedOmittedEvidence.rawMetadata.contentEquals(omittedEvidence.rawMetadata) shouldBe true
    }

    @Test
    fun `two source mappings materialize one shared canonical chapter for a new identity`() = runBlocking<Unit> {
        val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
        val projection = ReconcileLegacyChapterEvidence(
            LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
            ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
            repository,
            SourceTitleMappingRepositoryImpl(database),
        )
        val english = legacyInventory(7L, "mapping-1", "en", "Chapter 4")
        val portuguese = legacyInventory(8L, "mapping-2", "pt-BR", "Chapter 4")

        repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
        projection.execute(listOf(english, portuguese), observedAt = 100L).size shouldBe 2

        val canonical = repository.getByCanonicalTitleId("title-1").single()
        val englishVariant = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
        val portugueseVariant = requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/4"))
        englishVariant.canonicalChapterId shouldBe canonical.id
        portugueseVariant.canonicalChapterId shouldBe canonical.id
        evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title-1:7",
            "7:/chapter/4",
        )?.mappedCanonicalChapterId shouldBe canonical.id
        evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title-1:8",
            "8:/chapter/4",
        )?.mappedCanonicalChapterId shouldBe canonical.id
    }

    @Test
    fun `staged projection rejects a source id that does not own the materialized mapping`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val mismatched = legacyInventory(8L, "mapping-1", "pt-BR", "Vol. 1 Ch. 4")
            shouldThrow<IllegalArgumentException> {
                projection.execute(listOf(mismatched), observedAt = 200L)
            }
            repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            evidenceRepository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            repository.getVariantsBySourceMappingId("mapping-1") shouldBe emptyList()
        }

    @Test
    fun `staged projection rejects a stale manga id and unavailable source mapping`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val staleManga = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
                .copy(mihonMangaId = 999L)
            shouldThrow<IllegalArgumentException> {
                projection.execute(listOf(staleManga), observedAt = 200L)
            }
            driver.execute(
                null,
                "UPDATE tsuzuki_source_mappings SET availability = 'UNAVAILABLE' WHERE id = 'mapping-1'",
                0,
            ).await()
            shouldThrow<IllegalArgumentException> {
                projection.execute(listOf(legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")), observedAt = 300L)
            }
            repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
            evidenceRepository.getByCanonicalTitleId("title-1") shouldBe emptyList()
        }

    @Test
    fun `empty evidence projection rolls back changes from a failed operational callback`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val reconciler = ReconcileChapterEvidence(
                ParseCanonicalChapterLabel(),
                repository,
                evidenceRepository,
            )
            reconciler.executeAndProject("title-1", emptyList()) { observations ->
                observations.size shouldBe 0
                "no-op"
            } shouldBe "no-op"

            shouldThrow<IllegalStateException> {
                reconciler.executeAndProject("title-1", emptyList()) { observations ->
                    observations.size shouldBe 0
                    repository.upsertBatch(
                        chapters = listOf(chapter("chapter-empty-failed")),
                        variants = listOf(
                            variant(
                                id = "variant-empty-failed",
                                canonicalChapterId = "chapter-empty-failed",
                                sourceChapterId = "/empty-projected",
                            ),
                        ),
                    )
                    throw IllegalStateException("injected failure after operational projection")
                }
            }
            repository.getById("chapter-empty-failed") shouldBe null
            repository.getVariantBySourceIdentity(7L, "/empty-projected") shouldBe null
            evidenceRepository.getByCanonicalTitleId("title-1") shouldBe emptyList()
        }

    @Test
    fun `mapped source key becoming unreliable aborts and retains earlier evidence and variant`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projector = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val reliable = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
            projector.execute(listOf(reliable), observedAt = 100L).size shouldBe 1
            val firstVariant = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val firstEvidence = requireNotNull(
                evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "mihon-legacy:title-1:7",
                    "7:/chapter/4",
                ),
            )

            val unreliable = reliable.copy(
                chapters = reliable.chapters.map {
                    it.copy(rawName = "An unknown release", rawNumberHint = null)
                },
            )
            shouldThrow<IllegalStateException> {
                projector.execute(listOf(unreliable), observedAt = 200L)
            }
            repository.getVariantBySourceIdentity(7L, "/chapter/4") shouldBe firstVariant
            val retained = requireNotNull(
                evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "mihon-legacy:title-1:7",
                    "7:/chapter/4",
                ),
            )
            retained.evidence shouldBe firstEvidence.evidence
            retained.mappedCanonicalChapterId shouldBe firstEvidence.mappedCanonicalChapterId
            repository.getByCanonicalTitleId("title-1").size shouldBe 1
            projector.execute(listOf(reliable), observedAt = 300L).size shouldBe 1
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.id shouldBe firstVariant.id
        }

    @Test
    fun `delayed observation cannot revert a newer mapped chapter or its operational variant`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projector = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            val en = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
            val pt = legacyInventory(8L, "mapping-2", "pt-BR", "Vol. 1 Ch. 4")
            projector.execute(listOf(en, pt), observedAt = 200L)

            val newer = en.copy(chapters = en.chapters.map { it.copy(rawName = "Vol. 2 Ch. 4") })
            projector.execute(listOf(newer), observedAt = 300L)
            val chaptersBefore = repository.getByCanonicalTitleId("title-1")
            val englishBefore = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val portugueseBefore = requireNotNull(repository.getVariantBySourceIdentity(8L, "/chapter/4"))
            val evidenceBefore = evidenceRepository.getByCanonicalTitleId("title-1").map {
                it.evidence to it.mappedCanonicalChapterId
            }

            val delayed = en.copy(chapters = en.chapters.map { it.copy(rawName = "Vol. 1 Ch. 4 - delayed") })
            shouldThrow<IllegalArgumentException> {
                projector.execute(listOf(delayed), observedAt = 250L)
            }

            repository.getByCanonicalTitleId("title-1") shouldBe chaptersBefore
            repository.getVariantBySourceIdentity(7L, "/chapter/4") shouldBe englishBefore
            repository.getVariantBySourceIdentity(8L, "/chapter/4") shouldBe portugueseBefore
            evidenceRepository.getByCanonicalTitleId("title-1").map {
                it.evidence to it.mappedCanonicalChapterId
            } shouldBe evidenceBefore

            val cachedDelayed = en.copy(
                fetchStartedAtMillis = 150L,
                chapters = en.chapters.map { it.copy(rawName = "Vol. 1 Ch. 4 - stale cached response") },
            )
            shouldThrow<IllegalArgumentException> {
                projector.execute(listOf(cachedDelayed), observedAt = 400L)
            }
            repository.getByCanonicalTitleId("title-1") shouldBe chaptersBefore
            repository.getVariantBySourceIdentity(7L, "/chapter/4") shouldBe englishBefore

            projector.execute(listOf(newer), observedAt = 350L).size shouldBe 1
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.id shouldBe englishBefore.id
        }

    @Test
    fun `two conflicting releases with the same fetch start cannot overwrite mapped chapter`() =
        runBlocking<Unit> {
            val evidenceRepository = tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl(database)
            val projection = ReconcileLegacyChapterEvidence(
                LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                ReconcileChapterEvidence(ParseCanonicalChapterLabel(), repository, evidenceRepository),
                repository,
                SourceTitleMappingRepositoryImpl(database),
            )
            // Equal millisecond timestamps carry no ordering information. The
            // newer-looking provider label must not silently rehome a read chapter.
            val initial = legacyInventory(7L, "mapping-1", "en", "Vol. 1 Ch. 4")
                .copy(fetchStartedAtMillis = 200L)
            projection.execute(listOf(initial), observedAt = 250L).size shouldBe 1
            // Replaying the identical cached inventory is idempotent, even if
            // the caller happens to reconcile it at a later wall-clock time.
            projection.execute(listOf(initial), observedAt = 550L).size shouldBe 1
            val chaptersBefore = repository.getByCanonicalTitleId("title-1")
            val variantBefore = requireNotNull(repository.getVariantBySourceIdentity(7L, "/chapter/4"))
            val evidenceBefore = evidenceRepository.getByCanonicalTitleId("title-1").map {
                it.evidence to it.mappedCanonicalChapterId
            }

            val ambiguous = initial.copy(
                chapters = initial.chapters.map { it.copy(rawName = "Vol. 2 Ch. 4") },
            )
            shouldThrow<IllegalArgumentException> {
                projection.execute(listOf(ambiguous), observedAt = 600L)
            }

            repository.getByCanonicalTitleId("title-1") shouldBe chaptersBefore
            repository.getVariantBySourceIdentity(7L, "/chapter/4") shouldBe variantBefore
            evidenceRepository.getByCanonicalTitleId("title-1").map {
                it.evidence to it.mappedCanonicalChapterId
            } shouldBe evidenceBefore

            // A true later source fetch may still legitimately rehome the key.
            projection.execute(
                listOf(ambiguous.copy(fetchStartedAtMillis = 201L)),
                observedAt = 600L,
            ).size shouldBe 1
            repository.getVariantBySourceIdentity(7L, "/chapter/4")?.id shouldBe variantBefore.id
            repository.getByCanonicalTitleId("title-1").single { it.volume == 2 }.id shouldBe
                repository.getVariantBySourceIdentity(7L, "/chapter/4")?.canonicalChapterId
        }

    private fun legacyInventory(
        sourceId: Long,
        mappingId: String,
        language: String,
        label: String,
        url: String = "/chapter/4",
        rawNumberHint: Double? = 4.0,
    ) = SourceChapterInventory(
        sourceMappingId = mappingId,
        sourceId = sourceId,
        canonicalTitleId = "title-1",
        mihonMangaId = when (sourceId) {
            7L -> 11L
            8L -> 12L
            else -> 13L
        },
        language = language,
        sourceUrl = when (mappingId) {
            "mapping-1" -> "/title"
            "mapping-2" -> "/title-2"
            "foreign-mapping" -> "/foreign"
            else -> ""
        },
        chapters = listOf(
            SourceChapterSnapshot(
                sourceId = sourceId,
                sourceMappingId = mappingId,
                sourceChapterId = url,
                rawName = label,
                language = language,
                rawNumberHint = rawNumberHint,
            ),
        ),
    )

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
            mihonMangaId = 11L,
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
            mihonMangaId = 12L,
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
