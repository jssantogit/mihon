package tachiyomi.data.tsuzuki.integration

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
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.RatingIdentityEvidence
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedRating
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRating
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRatingSource
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import java.nio.file.Files

class CanonicalMetadataSnapshotRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: CanonicalMetadataSnapshotRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-metadata-snapshot")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalMetadataSnapshotRepositoryImplTest::class.java
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
        repository = CanonicalMetadataSnapshotRepositoryImpl(database)
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
    fun `resolved metadata snapshot round trips every detail-facing field`() = runBlocking {
        val rating = ProvenancedMetadata(
            value = ResolvedRating(
                value = 8.7,
                maxValue = 10.0,
                voteCount = 321,
                identityEvidence = RatingIdentityEvidence.CORROBORATED_RATING_ONLY,
            ),
            providerId = IntegrationId("mal"),
            externalId = "m1",
            attribution = "MAL",
        )
        val snapshot = CanonicalMetadataSnapshot(
            canonicalTitleId = "title",
            configurationFingerprint = "cfg-a",
            metadata = ResolvedMetadata(
                title = ProvenancedMetadata("Title", IntegrationId("kitsu"), "k1", "Kitsu"),
                synopsis = ProvenancedMetadata("Synopsis", IntegrationId("kitsu"), "k1", "Kitsu"),
                artworkUrl = ProvenancedMetadata("cover", IntegrationId("kitsu"), "k1", "Kitsu"),
                status = ProvenancedMetadata("COMPLETED", IntegrationId("mal"), "m1", "MAL"),
                format = ProvenancedMetadata("MANGA", IntegrationId("mal"), "m1", "MAL"),
                editorialChapterCount = ProvenancedMetadata(42, IntegrationId("kitsu"), "k1", "Kitsu"),
                rating = ProvenancedMetadata(8.7, IntegrationId("mal"), "m1", "MAL"),
                ratingDetails = rating,
                ratings = listOf(rating),
                tsuzukiRating = TsuzukiRating(
                    value = 8.7,
                    sources = listOf(
                        TsuzukiRatingSource(
                            providerId = "mal",
                            value = 8.7,
                            maxValue = 10.0,
                            voteCount = 321,
                            identityEvidence = RatingIdentityEvidence.CORROBORATED_RATING_ONLY,
                        ),
                    ),
                ),
                authors = ProvenancedMetadata(listOf("Author"), IntegrationId("mal"), "m1", "MAL"),
                artists = ProvenancedMetadata(listOf("Artist"), IntegrationId("mal"), "m1", "MAL"),
                genres = ProvenancedMetadata(listOf("Action"), IntegrationId("kitsu"), "k1", "Kitsu"),
                tags = ProvenancedMetadata(listOf("Tag"), IntegrationId("kitsu"), "k1", "Kitsu"),
                startDate = ProvenancedMetadata("2020", IntegrationId("mal"), "m1", "MAL"),
                endDate = ProvenancedMetadata("2024", IntegrationId("mal"), "m1", "MAL"),
                editorialVolumeCount = ProvenancedMetadata(10, IntegrationId("mal"), "m1", "MAL"),
                externalIds = mapOf(IntegrationId("kitsu") to "k1", IntegrationId("mal") to "m1"),
            ),
            refreshedAt = 1234L,
        )

        repository.upsertIfNewer(snapshot)

        repository.get("title") shouldBe snapshot
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
