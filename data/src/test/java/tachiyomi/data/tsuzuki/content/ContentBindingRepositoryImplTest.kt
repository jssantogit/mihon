package tachiyomi.data.tsuzuki.content

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
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import java.nio.file.Files

class ContentBindingRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: ContentBindingRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-binding-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ContentBindingRepositoryImplTest::class.java
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
            displayTitle = "Dandadan",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository = ContentBindingRepositoryImpl(database)
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
    fun `upsert get and mark unavailable preserve provider neutral binding`() = runBlocking<Unit> {
        val original = binding()

        repository.upsert(original)

        val stored = repository.get("title", AddonId("mangadex"))!!
        stored.id shouldBe original.id
        stored.providerTitleKey shouldBe original.providerTitleKey
        stored.runtimePayload.contentEquals(original.runtimePayload) shouldBe true
        repository.getByTitle("title").map { it.id } shouldBe listOf("binding")

        repository.markUnavailable("binding", updatedAt = 200L)

        val unavailable = repository.get("title", AddonId("mangadex"))!!
        unavailable.availability shouldBe ContentBindingAvailability.UNAVAILABLE
        unavailable.updatedAt shouldBe 200L
        unavailable.canonicalTitleId shouldBe "title"
    }

    private fun binding() = ContentBinding(
        id = "binding",
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        providerTitleKey = "7:/dandadan",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(7, 9, 11),
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
