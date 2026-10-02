package tachiyomi.data.tsuzuki.chapter

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
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
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshot
import java.nio.file.Files

class ChapterRefreshSnapshotRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: ChapterRefreshSnapshotRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-refresh-snapshot")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ChapterRefreshSnapshotRepositoryImplTest::class.java
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
            id = "title",
            displayTitle = "Title",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository = ChapterRefreshSnapshotRepositoryImpl(database)
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
    fun `title and binding snapshots round trip`() = runBlocking {
        val title = ChapterRefreshSnapshot(
            canonicalTitleId = "title",
            scopeKey = ChapterRefreshSnapshot.TITLE_SCOPE,
            fingerprint = null,
            configurationFingerprint = "config-a",
            observedAt = 100L,
            refreshedAt = 100L,
        )
        val binding = ChapterRefreshSnapshot(
            canonicalTitleId = "title",
            scopeKey = "binding:one",
            fingerprint = "inventory-a",
            configurationFingerprint = "config-a",
            observedAt = 90L,
            refreshedAt = 100L,
        )

        repository.upsertIfNewer(title)
        repository.upsertIfNewer(binding)

        repository.get("title", ChapterRefreshSnapshot.TITLE_SCOPE) shouldBe title
        repository.get("title", "binding:one") shouldBe binding
    }

    @Test
    fun `older snapshot cannot replace newer provider observation`() = runBlocking {
        val newer = ChapterRefreshSnapshot(
            canonicalTitleId = "title",
            scopeKey = "binding:one",
            fingerprint = "new",
            configurationFingerprint = "config-a",
            observedAt = 200L,
            refreshedAt = 210L,
        )
        val older = newer.copy(
            fingerprint = "old",
            observedAt = 100L,
            refreshedAt = 220L,
        )

        repository.upsertIfNewer(newer)
        repository.upsertIfNewer(older)

        repository.get("title", "binding:one") shouldBe newer
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
