package tachiyomi.data.tsuzuki.download

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
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import java.nio.file.Files

class CanonicalDownloadRepositoryImplTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: CanonicalDownloadRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-download-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalDownloadRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/" + nativeLibraryArchitecture() + "/libsqlitejdbc.so")!!
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
        database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            volume = null,
            title = null,
            type = "REGULAR",
            baseNumber = 1L,
            part = null,
            alphaSuffix = null,
            confidence = 1.0,
            createdAt = 1L,
            updatedAt = 1L,
            confirmationState = "CONFIRMED",
        )
        repository = CanonicalDownloadRepositoryImpl(database)
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
    fun canonicalDownloadSurvivesOriginAddonDisappearance() = runBlocking<Unit> {
        val artifact = CanonicalDownloadArtifact(
            canonicalChapterId = "chapter-1",
            localUri = "content://downloads/chapter-1.cbz",
            format = "CBZ",
            originatingAddonId = AddonId("mangadex"),
            originatingOptionKey = "option-1",
            completedAt = 100L,
            checksum = null,
        )
        repository.upsert(artifact)
        repository.get("chapter-1") shouldBe artifact
        repository.deleteOriginMetadata(AddonId("mangadex"))
        repository.get("chapter-1") shouldBe artifact.copy(
            originatingAddonId = null,
            originatingOptionKey = null,
        )
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture")
    }
}
