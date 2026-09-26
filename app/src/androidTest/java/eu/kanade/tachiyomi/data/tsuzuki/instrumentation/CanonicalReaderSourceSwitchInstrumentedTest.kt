package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayload
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import mihon.app.di.AppBindings
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentBindingRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentPreferenceRepositoryImpl
import tachiyomi.domain.chapter.model.CanonicalChapterConfirmation
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** Runs production canonical Reader and Mihon source adapters against a disposable local fixture. */
@RunWith(AndroidJUnit4::class)
class CanonicalReaderSourceSwitchInstrumentedTest {

    @Test(timeout = 180_000L)
    fun syntheticMihonSourceLoadsObservablePagesInReaderActivity() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val loaded = awaitReaderPages(reader, expectedSource = fixture.source.name, expectedCount = 10)
            assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
            assertEquals(0, loaded.first)
            awaitImagePixels(Color.rgb(220, 40, 40))

            report(
                scenario = "SYNTHETIC_SOURCE_READER",
                pageCount = loaded.second,
                positionIndex = loaded.first,
            )
        }
    }

    private fun requireOptIn() {
        assertEquals(
            "This destructive Reader fixture is restricted to the isolated source-switch emulator lane",
            "true",
            InstrumentationRegistry.getArguments().getString("androidSourceSwitchOptIn"),
        )
        assertEquals(
            "The Reader fixture may only write to the dedicated debug emulator package",
            EXPECTED_TARGET_PACKAGE,
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }

    private fun withFixture(block: (SourceSwitchFixture) -> Unit) {
        val fixture = SourceSwitchFixture.create()
        try {
            block(fixture)
        } finally {
            finishActivities()
            fixture.close()
        }
    }

    private fun awaitReaderPages(
        reader: ReaderActivity,
        expectedSource: String,
        expectedCount: Int,
    ): Pair<Int, Int> = awaitValue("loaded Reader pages for $expectedSource") {
        val state = reader.viewModel.state.value
        val chapter = state.currentChapter ?: return@awaitValue null
        val pages = chapter.pages ?: return@awaitValue null
        if (state.source?.name != expectedSource || pages.size != expectedCount) return@awaitValue null
        val index = state.currentPage - 1
        if (index !in pages.indices || pages[index].status != Page.State.Ready || pages[index].stream == null) {
            return@awaitValue null
        }
        assertTrue("The Reader must publish a loaded chapter state", chapter.state is ReaderChapter.State.Loaded)
        index to pages.size
    }

    private fun awaitImagePixels(expectedColor: Int) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        awaitValue("Reader to render the synthetic page image") {
            val screenshot = device.takeScreenshot() ?: return@awaitValue null
            val color = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
            screenshot.recycle()
            if (Color.red(color) > 160 && Color.green(color) < 80 && Color.blue(color) < 80) {
                true
            } else {
                null
            }
        }
        assertTrue("Fixture image color must be distinguishable from the Reader background", expectedColor != Color.BLACK)
    }

    private fun report(scenario: String, pageCount: Int, positionIndex: Int) {
        assertTrue("At least one real page must have been loaded", pageCount > 0)
        assertTrue("The observed page index must be within the loaded page list", positionIndex in 0 until pageCount)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "ANDROID_SOURCE_SWITCH|scenario=$scenario|pages=LOADED|pageCount=$pageCount" +
                        "|position=OBSERVABLE|positionIndex=$positionIndex|outcome=PASS",
                )
            },
        )
    }

    private fun finishActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
                .flatMap(lifecycle::getActivitiesInStage)
                .distinct()
                .forEach(Activity::finish)
        }
        SystemClock.sleep(300L)
    }

    private fun <T : Activity> awaitActivity(type: Class<T>): T = awaitValue("resumed ${type.simpleName}") {
        var activity: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance(type)
                .firstOrNull()
        }
        activity
    }

    private fun <T> awaitValue(description: String, query: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            query()?.let { return it }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private class SourceSwitchFixture private constructor(
        private val app: App,
        private val driver: SqlDriver,
        private val database: Database,
        private val server: MockWebServer,
        val source: ReaderFixtureHttpSource,
        val title: CanonicalTitle,
        val chapter: CanonicalChapter,
        private val originalReaderMode: Int,
        private val installedExtensions: MutableStateFlow<Map<String, Extension.Installed>>,
        private val priorExtensions: Map<String, Extension.Installed>,
    ) {

        fun launchReader() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(
                ReaderActivity.newCanonicalIntent(context, chapter.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }

        fun close() {
            try {
                installedExtensions.value = priorExtensions
                awaitValue("test source to be removed from the production SourceManager") {
                    runBlocking { app.graph.sourceManager.get(source.id) == null }
                }
                runBlocking {
                    database.tsuzuki_titlesQueries.deleteTsuzukiTitle(title.id)
                    database.mangasQueries.deleteNonLibraryManga(listOf(source.id), keepReadManga = false)
                }
            } finally {
                app.graph.readerPreferences.defaultReadingMode.set(originalReaderMode)
                server.shutdown()
                driver.close()
            }
        }

        companion object {
            fun create(): SourceSwitchFixture {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                assertEquals(EXPECTED_TARGET_PACKAGE, context.packageName)
                val app = context.applicationContext as App
                val extensionManager = app.graph.extensionManager
                runBlocking { extensionManager.getInstalledExtensions() }
                val addonRepository = app.graph.addonRepository
                assertTrue(
                    "The isolated source-switch emulator must start with no installed real Add-ons",
                    runBlocking { addonRepository.snapshot() }.isEmpty(),
                )
                @Suppress("UNCHECKED_CAST")
                val installedExtensions = ExtensionManager::class.java
                    .getDeclaredField("installedExtensionMapFlow")
                    .apply { isAccessible = true }
                    .get(extensionManager) as MutableStateFlow<Map<String, Extension.Installed>>
                val priorExtensions = installedExtensions.value

                val runId = UUID.randomUUID().toString().replace("-", "").take(12)
                val server = MockWebServer()
                server.dispatcher = ReaderFixtureDispatcher()
                server.start()
                val baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
                val source = ReaderFixtureHttpSource(
                    displayName = "Synthetic Reader Source $runId",
                    baseUrl = baseUrl,
                    client = OkHttpClient(),
                )
                val addonId = AddonId("test.tsuzuki.reader.$runId")
                installedExtensions.value = priorExtensions + (
                    addonId.value to Extension.Installed(
                        name = source.name,
                        pkgName = addonId.value,
                        versionName = "instrumented-test",
                        versionCode = 1L,
                        libVersion = 1.6,
                        lang = source.lang,
                        isNsfw = false,
                        pkgFactory = null,
                        sources = listOf(source),
                        icon = null,
                        isShared = false,
                    )
                    )
                awaitValue("synthetic source and Add-on registration") {
                    val registered = runBlocking { app.graph.sourceManager.get(source.id) }
                    val addon = runBlocking { addonRepository.snapshot() }.firstOrNull { it.id == addonId }
                    registered === source && addon?.enabled == true
                }

                val driver = AppBindings.providesSqlDriver(context)
                try {
                    val database = AppBindings.providesDatabase(driver)
                    val titleId = "android-source-switch-$runId"
                    val now = System.currentTimeMillis()
                    val title = CanonicalTitle(
                        id = titleId,
                        displayTitle = "Synthetic Reader Fixture $runId",
                        identityState = CanonicalIdentityState.SOURCE_ONLY,
                        createdAt = now,
                        updatedAt = now,
                    )
                    val chapter = CanonicalChapter(
                        id = "$titleId-chapter-1",
                        canonicalTitleId = titleId,
                        displayNumber = "1",
                        type = CanonicalChapterType.REGULAR,
                        baseNumber = 1,
                        confidence = 1.0,
                        createdAt = now,
                        updatedAt = now,
                        confirmation = CanonicalChapterConfirmation.CONFIRMED,
                    )
                    val titles = CanonicalTitleRepositoryImpl(database)
                    val canonicalChapters = CanonicalChapterRepositoryImpl(database)
                    val mangaRepository = MangaRepositoryImpl(database)
                    val bindingRepository = ContentBindingRepositoryImpl(database)
                    val preferences = ContentPreferenceRepositoryImpl(database)
                    val sourceManga = runBlocking {
                        titles.insert(title)
                        canonicalChapters.upsert(chapter)
                        mangaRepository.insertNetworkManga(
                            listOf(
                                Manga.create().copy(
                                    source = source.id,
                                    url = "/reader-$runId/manga",
                                    title = title.displayTitle,
                                    favorite = false,
                                    dateAdded = now,
                                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                                ),
                            ),
                        ).single()
                    }
                    runBlocking {
                        bindingRepository.upsert(
                            ContentBinding(
                                id = "binding-$runId",
                                canonicalTitleId = titleId,
                                addonId = addonId,
                                providerTitleKey = "${source.id}:${sourceManga.url}",
                                matchConfidence = 1.0,
                                verifiedByUser = true,
                                availability = ContentBindingAvailability.AVAILABLE,
                                runtimePayload = MihonContentBindingPayloadCodec.encode(
                                    MihonContentBindingPayload(
                                        sourceId = source.id,
                                        mihonMangaId = sourceManga.id,
                                        sourceUrl = sourceManga.url,
                                        language = source.lang,
                                    ),
                                ),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        preferences.upsert(
                            ContentPreference(
                                canonicalTitleId = titleId,
                                preferredAddonId = addonId,
                                preferredLanguage = source.lang,
                                updatedAt = now,
                            ),
                        )
                    }
                    val originalReaderMode = app.graph.readerPreferences.defaultReadingMode.get()
                    app.graph.readerPreferences.defaultReadingMode.set(ReadingMode.LEFT_TO_RIGHT.flagValue)
                    return SourceSwitchFixture(
                        app = app,
                        driver = driver,
                        database = database,
                        server = server,
                        source = source,
                        title = title,
                        chapter = chapter,
                        originalReaderMode = originalReaderMode,
                        installedExtensions = installedExtensions,
                        priorExtensions = priorExtensions,
                    )
                } catch (error: Throwable) {
                    installedExtensions.value = priorExtensions
                    server.shutdown()
                    driver.close()
                    throw error
                }
            }
        }
    }

    private class ReaderFixtureDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path.endsWith("/manga") -> textResponse("/reader-chapter-1\tChapter 1\t1\tSynthetic group")
                path.endsWith("/chapter-1") -> textResponse(
                    (0 until 10).joinToString("\n") { "/reader-image/$it.png" },
                )
                path.startsWith("/reader-image/") -> imageResponse()
                else -> MockResponse.Builder().code(404).body("fixture route missing").build()
            }
        }

        private fun textResponse(body: String) = MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/plain; charset=utf-8")
            .body(body)
            .build()

        private fun imageResponse(): MockResponse {
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(220, 40, 40))
            }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            return MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body(Buffer().write(bytes))
                .build()
        }
    }

    private class ReaderFixtureHttpSource(
        private val displayName: String,
        override val baseUrl: String,
        override val client: OkHttpClient,
    ) : HttpSource() {
        override val name: String = displayName
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override fun getFilterList(): FilterList = FilterList()

        @Deprecated("instrumented source fixture")
        override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}")

        @Deprecated("instrumented source fixture")
        override fun chapterListParse(response: Response): List<SChapter> = response.body.string()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val (url, name, number, group) = line.split('\t', limit = 4)
                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    chapter_number = number.toFloat()
                    scanlator = group
                }
            }
            .toList()

        @Deprecated("instrumented source fixture")
        override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}")

        @Deprecated("instrumented source fixture")
        override fun pageListParse(response: Response): List<Page> = response.body.string()
            .lineSequence()
            .filter(String::isNotBlank)
            .mapIndexed { index, path -> Page(index, path, imageUrl = "$baseUrl$path") }
            .toList()
    }

    private companion object {
        const val EXPECTED_TARGET_PACKAGE = "app.mihon.dev"
        const val UI_TIMEOUT_MS = 60_000L
    }
}
