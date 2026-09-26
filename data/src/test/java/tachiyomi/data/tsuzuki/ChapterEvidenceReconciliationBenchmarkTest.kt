package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
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
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Locale

/** Opt-in SQLDelight in-memory benchmark. Set TSUZUKI_CHAPTER_BENCHMARK=true to execute. */
class ChapterEvidenceReconciliationBenchmarkTest {

    private lateinit var driver: SqlDriver
    private lateinit var queryCounter: SqlDriverQueryCounter
    private lateinit var database: Database
    private lateinit var evidenceRepository: ChapterEvidenceRepositoryImpl
    private lateinit var chapterRepository: CanonicalChapterRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-sqlite-jdbc-benchmark")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ChapterEvidenceReconciliationBenchmarkTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())

        queryCounter = SqlDriverQueryCounter(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        driver = queryCounter.driver
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
    @EnabledIfEnvironmentVariable(named = "TSUZUKI_CHAPTER_BENCHMARK", matches = "true")
    fun `measure reconciliations at 100 500 and 1000 existing observations`() = runBlocking<Unit> {
        val samples = mutableListOf<String>()
        val label = System.getenv("TSUZUKI_BENCHMARK_LABEL") ?: "unlabeled"
        val output = java.io.File("build/reports/tsuzuki/chapter-evidence-benchmark.txt")
        output.parentFile.mkdirs()
        samples += "revision_label=$label"
        samples += "environment java=${System.getProperty("java.version")} " +
            "os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} " +
            "processors=${Runtime.getRuntime().availableProcessors()} driver=JdbcSqliteDriver(IN_MEMORY)"

        for (size in listOf(100, 500, 1_000)) {
            val titleId = "benchmark-title-$size"
            val observations = seedExistingInventory(titleId, size)
            val reconciler = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapterRepository,
                evidenceRepository = evidenceRepository,
            )

            reconciler.execute(titleId, observations.take(25))
            val sampleResults = mutableListOf<BenchmarkSample>()
            repeat(2) {
                queryCounter.reset()
                val startedAt = System.nanoTime()
                reconciler.execute(titleId, observations)
                val elapsedNanos = System.nanoTime() - startedAt
                sampleResults += BenchmarkSample(elapsedNanos, queryCounter.snapshot())
            }

            chapterRepository.getByCanonicalTitleId(titleId).size shouldBe size
            evidenceRepository.getByCanonicalTitleId(titleId).size shouldBe size
            sampleResults[0].queryCounts shouldBe sampleResults[1].queryCounts

            val elapsedMs = sampleResults.joinToString(",") { formatMillis(it.elapsedNanos) }
            val counts = sampleResults.first().queryCounts
            samples += "sample size=$size warmup=25 elapsed_ms=[$elapsedMs] " +
                "execute=${counts.execute} executeQuery=${counts.executeQuery} total_sql=${counts.total}"
        }

        val report = samples.joinToString(separator = "\n", postfix = "\n")
        output.writeText(report)
        println(report)
    }

    private suspend fun seedExistingInventory(titleId: String, size: Int): List<ChapterEvidence> {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = titleId,
            displayTitle = titleId,
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val observations = (1..size).map { index ->
            ChapterEvidence(
                id = "$titleId-evidence-$index",
                canonicalTitleId = titleId,
                producerKind = ProducerKind.ADDON,
                producerId = "$titleId-provider",
                externalChapterKey = "chapter-$index",
                rawLabel = "Chapter $index",
                rawNumber = null,
                volume = null,
                title = null,
                observedAt = 1L,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            )
        }

        database.transaction {
            observations.forEachIndexed { index, observation ->
                val chapterId = "$titleId-chapter-${index + 1}"
                database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
                    id = chapterId,
                    canonicalTitleId = titleId,
                    displayNumber = (index + 1).toString(),
                    volume = null,
                    title = null,
                    type = "REGULAR",
                    baseNumber = (index + 1).toLong(),
                    part = null,
                    alphaSuffix = null,
                    confidence = 1.0,
                    createdAt = 1L,
                    updatedAt = 1L,
                    confirmationState = "CONFIRMED",
                )
                database.tsuzuki_chapter_evidenceQueries.upsertTsuzukiChapterEvidence(
                    id = observation.id,
                    canonicalTitleId = titleId,
                    producerKind = observation.producerKind.name,
                    producerId = observation.producerId,
                    externalChapterKey = observation.externalChapterKey,
                    rawLabel = observation.rawLabel,
                    rawNumber = observation.rawNumber,
                    volume = null,
                    title = null,
                    observedAt = observation.observedAt,
                    confidence = observation.confidence,
                    authorityClass = observation.authority.name,
                    mappedCanonicalChapterId = chapterId,
                    rawMetadata = byteArrayOf(),
                )
            }
        }
        return observations
    }

    private fun formatMillis(elapsedNanos: Long): String =
        String.format(Locale.ROOT, "%.3f", elapsedNanos / 1_000_000.0)

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    private data class BenchmarkSample(val elapsedNanos: Long, val queryCounts: QueryCounts)

    private data class QueryCounts(val execute: Int, val executeQuery: Int) {
        val total: Int
            get() = execute + executeQuery
    }

    private class SqlDriverQueryCounter(delegate: SqlDriver) {
        private var executeCount = 0
        private var executeQueryCount = 0

        val driver = Proxy.newProxyInstance(
            SqlDriver::class.java.classLoader,
            arrayOf(SqlDriver::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "execute" -> executeCount++
                "executeQuery" -> executeQueryCount++
            }
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } as SqlDriver

        fun reset() {
            executeCount = 0
            executeQueryCount = 0
        }

        fun snapshot() = QueryCounts(execute = executeCount, executeQuery = executeQueryCount)
    }
}
