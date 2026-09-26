package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayload
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentBindingRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentPreferenceRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
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

    @Test(timeout = 240_000L)
    fun sourceAtoBLoadsPagesAndPreservesCanonicalChapterAndObservedPosition() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val first = awaitReaderPages(reader, expectedSource = fixture.sourceA.name, expectedCount = 10)
            assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
            awaitImagePixels(Color.rgb(220, 40, 40))
            val positionBefore = advanceToPage(reader, 4)
            val progressBefore = awaitValue("canonical progress for observed page 4") {
                runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
                    ?.takeIf { it.lastPageRead >= positionBefore.toLong() }
            }
            val preferenceBefore = runBlocking { fixture.preferences.get(fixture.title.id) }
            assertEquals(fixture.addonA, preferenceBefore?.preferredAddonId)

            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            val after = awaitReaderPages(reader, fixture.sourceB.name, expectedCount = 10)
            assertEquals("An equal-length source switch must preserve the observed page index", positionBefore, after.first)
            assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
            awaitImagePixels(Color.rgb(35, 70, 225))

            assertEquals("Switch must leave the preference provisional until user confirmation", fixture.addonA, runBlocking {
                fixture.preferences.get(fixture.title.id)?.preferredAddonId
            })
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(
                "Successful alternate source preparation should ask before replacing the saved preference",
                device.wait(Until.hasObject(By.text("Set preferred Add-on?")), UI_TIMEOUT_MS),
            )
            clickText(device, "OK")
            awaitValue("confirmed preferred Add-on to persist") {
                runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId == fixture.addonB }
            }

            val progressAfter = runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
            assertEquals("An incomplete chapter must remain unread", false, progressAfter?.read)
            assertEquals(
                "Source switch must preserve canonical progress",
                progressBefore.lastPageRead,
                progressAfter?.lastPageRead,
            )
            assertEquals(
                fixture.chapter.id,
                runBlocking { fixture.reading.getHistory(fixture.chapter.id)?.canonicalChapterId },
            )
            assertEquals(
                "The old Reader instance remains the active Activity",
                reader,
                awaitActivity(ReaderActivity::class.java),
            )
            report(
                scenario = "SOURCE_A_TO_B",
                pageCount = after.second,
                positionIndex = after.first,
                details = "sourceAPageCount=${first.second}|sourceBPageCount=${after.second}" +
                    "|positionBefore=$positionBefore|positionAfter=${after.first}",
            )
        }
    }

    @Test(timeout = 240_000L)
    fun emptyOrFailingSourceKeepsPreviouslyLoadedReaderSession() {
        requireOptIn()
        withFixture(sourceBBehavior = FixtureBehavior(pageCount = 0)) { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val initial = awaitReaderPages(reader, fixture.sourceA.name, 10)
            val retiredCandidate = reader.viewModel.state.value.currentChapter!!.pages!![initial.first]
            val progressBefore = runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
            val historyBefore = runBlocking { fixture.reading.getHistory(fixture.chapter.id) }
            val emptyPagesBefore = fixture.dispatcher.pageRequestCount(fixture.sourceB.token)
            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            awaitSourceRequest(fixture.dispatcher, fixture.sourceB.token, emptyPagesBefore)
            assertReaderSession(reader, fixture, fixture.sourceA.name, initial.first, 10)
            awaitImagePixels(Color.rgb(220, 40, 40))

            fixture.dispatcher.setBehavior(fixture.sourceB.token, FixtureBehavior(pageCount = 10, pageListStatus = 503))
            val failedPagesBefore = fixture.dispatcher.pageRequestCount(fixture.sourceB.token)
            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            awaitSourceRequest(fixture.dispatcher, fixture.sourceB.token, failedPagesBefore)
            assertReaderSession(reader, fixture, fixture.sourceA.name, initial.first, 10)
            assertEquals("A failed replacement must not change the source preference", fixture.addonA, runBlocking {
                fixture.preferences.get(fixture.title.id)?.preferredAddonId
            })
            assertEquals("A failed replacement must not change canonical progress", progressBefore, runBlocking {
                fixture.reading.getProgress(fixture.chapter.id)
            })
            assertEquals("A failed replacement must not record history", historyBefore, runBlocking {
                fixture.reading.getHistory(fixture.chapter.id)
            })
            dismissSourceSelectorThroughReaderUi()
            awaitImagePixels(Color.rgb(220, 40, 40))
            assertSame("Failure must not replace the published chapter object", retiredCandidate.chapter, reader.viewModel.state.value.currentChapter!!.pages!![initial.first].chapter)
            report("EMPTY_OR_FAILING", 10, initial.first, details = "session=PREVIOUS_PRESERVED")
        }
    }

    @Test(timeout = 240_000L)
    fun pageCountDifferenceClampsPositionToValidPage() {
        requireOptIn()
        withFixture(sourceBBehavior = FixtureBehavior(pageCount = 3)) { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val first = awaitReaderPages(reader, fixture.sourceA.name, 10)
            val positionBefore = advanceToPage(reader, 8)
            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            val after = awaitReaderPages(reader, fixture.sourceB.name, 3)
            awaitImagePixels(Color.rgb(35, 70, 225))
            assertTrue("The published page index must be valid for the shorter source", after.first in 0 until after.second)
            assertEquals("A shorter source must clamp the prior index", minOf(positionBefore, after.second - 1), after.first)
            assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
            report(
                scenario = "PAGE_COUNT_CLAMP",
                pageCount = after.second,
                positionIndex = after.first,
                details = "sourceAPageCount=${first.second}|sourceBPageCount=${after.second}" +
                    "|positionBefore=$positionBefore|positionAfter=${after.first}",
            )
        }
    }

    @Test(timeout = 240_000L)
    fun retiredSourceCallbackCannotChangePublishedSession() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val sourceA = awaitReaderPages(reader, fixture.sourceA.name, 10)
            val retiredPage = reader.viewModel.state.value.currentChapter!!.pages!![sourceA.first]
            retiredPage.chapter.ref()
            advanceToPage(reader, 3)
            val progressBefore = awaitValue("canonical progress before switching source") {
                runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
                    ?.takeIf { it.lastPageRead >= 3L }
            }
            try {
                chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
                val sourceB = awaitReaderPages(reader, fixture.sourceB.name, 10)
                val progressAfterPublish = runBlocking { fixture.reading.getProgress(fixture.chapter.id) }

                // Inject the same Activity callback a retired viewer would deliver after publication.
                InstrumentationRegistry.getInstrumentation().runOnMainSync { reader.onPageSelected(retiredPage) }
                SystemClock.sleep(500L)
                val current = reader.viewModel.state.value.currentChapter
                assertEquals(
                    "Retired page callback must not activate the old chapter",
                    fixture.sourceB.name,
                    reader.viewModel.state.value.source?.name,
                )
                assertNotSame("The active viewer must keep the newly published chapter", retiredPage.chapter, current)
                assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
                assertEquals("Old callback must not change active page position", sourceB.first, reader.viewModel.state.value.currentPage - 1)
                assertEquals(progressAfterPublish, runBlocking { fixture.reading.getProgress(fixture.chapter.id) })
                assertTrue(
                    "A previously observed canonical progress value must remain available",
                    progressBefore.lastPageRead >= 3L,
                )
                report("RETIRED_CALLBACK", sourceB.second, sourceB.first)
            } finally {
                retiredPage.chapter.unref()
            }
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

    private fun withFixture(
        sourceBBehavior: FixtureBehavior = FixtureBehavior(pageCount = 10),
        block: (SourceSwitchFixture) -> Unit,
    ) {
        val fixture = SourceSwitchFixture.create(sourceBBehavior)
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
        val observedColor = awaitValue("Reader to render the synthetic page image") {
            val screenshot = device.takeScreenshot() ?: return@awaitValue null
            val color = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
            screenshot.recycle()
            val expectedRed = Color.red(expectedColor)
            val expectedGreen = Color.green(expectedColor)
            val expectedBlue = Color.blue(expectedColor)
            if (
                kotlin.math.abs(Color.red(color) - expectedRed) < 45 &&
                kotlin.math.abs(Color.green(color) - expectedGreen) < 45 &&
                kotlin.math.abs(Color.blue(color) - expectedBlue) < 45
            ) {
                color
            } else {
                null
            }
        }
        assertTrue(
            "Reader screenshot must contain the loaded fixture page color",
            kotlin.math.abs(Color.red(observedColor) - Color.red(expectedColor)) < 45 &&
                kotlin.math.abs(Color.green(observedColor) - Color.green(expectedColor)) < 45 &&
                kotlin.math.abs(Color.blue(observedColor) - Color.blue(expectedColor)) < 45,
        )
    }

    private fun advanceToPage(reader: ReaderActivity, requestedIndex: Int): Int {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val startIndex = reader.viewModel.state.value.currentPage - 1
        assertTrue("Current Reader page index must be observable before advancing", startIndex >= 0)
        if (requestedIndex > startIndex) {
            repeat(requestedIndex - startIndex) {
                assertTrue("Reader must accept a forward page key", device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
            }
        }
        return awaitValue("Reader to display requested page $requestedIndex") {
            val state = reader.viewModel.state.value
            val chapter = state.currentChapter ?: return@awaitValue null
            val pages = chapter.pages ?: return@awaitValue null
            val index = state.currentPage - 1
            if (index != requestedIndex || index !in pages.indices) return@awaitValue null
            val page = pages[index]
            if (page.status != Page.State.Ready || page.stream == null) return@awaitValue null
            index
        }
    }

    private fun chooseSourceThroughReaderUi(reader: ReaderActivity, sourceName: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (!device.hasObject(By.text("Choose reading source"))) {
            if (!reader.viewModel.state.value.menuVisible) {
                val bounds = device.displayWidth to device.displayHeight
                device.click(bounds.first / 2, bounds.second / 2)
                awaitValue("Reader menu to become visible") { reader.viewModel.state.value.menuVisible }
            }
            assertTrue(
                "Reader overflow action must be present",
                device.wait(Until.hasObject(By.desc("More options")), UI_TIMEOUT_MS),
            )
            device.findObject(By.desc("More options")).click()
            assertTrue(
                "Change source action must be exposed from the Reader overflow menu",
                device.wait(Until.hasObject(By.text("Change source")), UI_TIMEOUT_MS),
            )
            device.findObject(By.text("Change source")).click()
        }
        assertTrue(
            "The canonical reading-source selector must be open",
            device.wait(Until.hasObject(By.text("Choose reading source")), UI_TIMEOUT_MS),
        )
        assertTrue(
            "The selected synthetic source must be offered in the UI",
            device.wait(Until.hasObject(By.text(sourceName)), UI_TIMEOUT_MS),
        )
        device.findObject(By.text(sourceName)).click()
    }

    private fun clickText(device: UiDevice, text: String) {
        assertTrue("Expected visible UI text '$text'", device.wait(Until.hasObject(By.text(text)), UI_TIMEOUT_MS))
        device.findObject(By.text(text)).click()
    }

    private fun dismissSourceSelectorThroughReaderUi() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.hasObject(By.text("Choose reading source")))
        device.pressBack()
        assertTrue(
            "Reader source selector must be dismissible after a recoverable failure",
            device.wait(Until.gone(By.text("Choose reading source")), UI_TIMEOUT_MS),
        )
    }

    private fun assertReaderSession(
        reader: ReaderActivity,
        fixture: SourceSwitchFixture,
        expectedSource: String,
        expectedIndex: Int,
        expectedPageCount: Int,
    ) {
        val loaded = awaitReaderPages(reader, expectedSource, expectedPageCount)
        assertEquals(
            "Reader must retain the canonical chapter identity",
            fixture.chapter.id,
            reader.intent.getStringExtra("canonical_chapter"),
        )
        assertEquals("Reader must retain the previous page index", expectedIndex, loaded.first)
    }

    private fun awaitSourceRequest(dispatcher: ReaderFixtureDispatcher, token: String, before: Int) {
        awaitValue("fixture page-list request for source $token") {
            dispatcher.pageRequestCount(token).let { count -> if (count > before) count else null }
        }
    }

    private fun report(scenario: String, pageCount: Int, positionIndex: Int, details: String = "") {
        assertTrue("At least one real page must have been loaded", pageCount > 0)
        assertTrue("The observed page index must be within the loaded page list", positionIndex in 0 until pageCount)
        val detailSuffix = details.takeIf(String::isNotBlank)?.let { "|$it" }.orEmpty()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "ANDROID_SOURCE_SWITCH|scenario=$scenario|pages=LOADED|pageCount=$pageCount" +
                    "|position=OBSERVABLE|positionIndex=$positionIndex$detailSuffix|outcome=PASS",
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
        val dispatcher: ReaderFixtureDispatcher,
        val sourceA: ReaderFixtureHttpSource,
        val sourceB: ReaderFixtureHttpSource,
        val addonA: AddonId,
        val addonB: AddonId,
        val title: CanonicalTitle,
        val chapter: CanonicalChapter,
        val preferences: ContentPreferenceRepositoryImpl,
        val reading: CanonicalReadingRepositoryImpl,
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
                awaitSourceSwitchFixtureValue("test sources to be removed from the production SourceManager") {
                    runBlocking {
                        app.graph.sourceManager.get(sourceA.id) == null && app.graph.sourceManager.get(sourceB.id) == null
                    }
                }
                runBlocking {
                    database.tsuzuki_titlesQueries.deleteTsuzukiTitle(title.id)
                    database.mangasQueries.deleteNonLibraryManga(
                        listOf(sourceA.id, sourceB.id),
                        keepReadManga = 0L,
                    )
                }
            } finally {
                app.graph.readerPreferences.defaultReadingMode.set(originalReaderMode)
                server.close()
                driver.close()
            }
        }

        companion object {
            fun create(sourceBBehavior: FixtureBehavior = FixtureBehavior(pageCount = 10)): SourceSwitchFixture {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                assertEquals(
                    CanonicalReaderSourceSwitchInstrumentedTest.EXPECTED_TARGET_PACKAGE,
                    context.packageName,
                )
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
                val dispatcher = ReaderFixtureDispatcher(
                    mapOf(
                        "reader-$runId-a" to FixtureBehavior(pageCount = 10, imageColor = Color.rgb(220, 40, 40)),
                        "reader-$runId-b" to sourceBBehavior.copy(imageColor = Color.rgb(35, 70, 225)),
                    ),
                )
                server.dispatcher = dispatcher
                server.start()
                val baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
                val sourceA = ReaderFixtureHttpSource(
                    displayName = "Synthetic Reader A $runId",
                    token = "reader-$runId-a",
                    baseUrl = baseUrl,
                    client = OkHttpClient(),
                )
                val sourceB = ReaderFixtureHttpSource(
                    displayName = "Synthetic Reader B $runId",
                    token = "reader-$runId-b",
                    baseUrl = baseUrl,
                    client = OkHttpClient(),
                )
                val addonA = AddonId("test.tsuzuki.reader.$runId.a")
                val addonB = AddonId("test.tsuzuki.reader.$runId.b")
                val extensionA = Extension.Installed(
                    name = sourceA.name,
                    pkgName = addonA.value,
                    versionName = "instrumented-test",
                    versionCode = 1L,
                    libVersion = 1.6,
                    lang = sourceA.lang,
                    isNsfw = false,
                    pkgFactory = null,
                    sources = listOf(sourceA),
                    icon = null,
                    isShared = false,
                )
                val extensionB = Extension.Installed(
                    name = sourceB.name,
                    pkgName = addonB.value,
                    versionName = "instrumented-test",
                    versionCode = 1L,
                    libVersion = 1.6,
                    lang = sourceB.lang,
                    isNsfw = false,
                    pkgFactory = null,
                    sources = listOf(sourceB),
                    icon = null,
                    isShared = false,
                )
                installedExtensions.value = priorExtensions + (addonA.value to extensionA) + (addonB.value to extensionB)
                awaitSourceSwitchFixtureValue("synthetic sources and Add-ons registration") {
                    val registeredA = runBlocking { app.graph.sourceManager.get(sourceA.id) }
                    val registeredB = runBlocking { app.graph.sourceManager.get(sourceB.id) }
                    val addons = runBlocking { addonRepository.snapshot() }
                    registeredA === sourceA && registeredB === sourceB &&
                        addons.any { it.id == addonA && it.enabled } && addons.any { it.id == addonB && it.enabled }
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
                    val sourceMangas = runBlocking {
                        titles.insert(title)
                        canonicalChapters.upsert(chapter)
                        mangaRepository.insertNetworkManga(
                            listOf(
                                Manga.create().copy(
                                    source = sourceA.id,
                                    url = "/reader/${sourceA.token}/manga",
                                    title = "${title.displayTitle} A",
                                    favorite = false,
                                    dateAdded = now,
                                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                                ),
                                Manga.create().copy(
                                    source = sourceB.id,
                                    url = "/reader/${sourceB.token}/manga",
                                    title = "${title.displayTitle} B",
                                    favorite = false,
                                    dateAdded = now,
                                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                                ),
                            ),
                        ).associateBy(Manga::source)
                    }
                    runBlocking {
                        bindingRepository.upsert(
                            ContentBinding(
                                id = "binding-$runId-a",
                                canonicalTitleId = titleId,
                                addonId = addonA,
                                providerTitleKey = "${sourceA.id}:${sourceMangas.getValue(sourceA.id).url}",
                                matchConfidence = 1.0,
                                verifiedByUser = true,
                                availability = ContentBindingAvailability.AVAILABLE,
                                runtimePayload = MihonContentBindingPayloadCodec.encode(
                                    MihonContentBindingPayload(
                                        sourceId = sourceA.id,
                                        mihonMangaId = sourceMangas.getValue(sourceA.id).id,
                                        sourceUrl = sourceMangas.getValue(sourceA.id).url,
                                        language = sourceA.lang,
                                    ),
                                ),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        bindingRepository.upsert(
                            ContentBinding(
                                id = "binding-$runId-b",
                                canonicalTitleId = titleId,
                                addonId = addonB,
                                providerTitleKey = "${sourceB.id}:${sourceMangas.getValue(sourceB.id).url}",
                                matchConfidence = 1.0,
                                verifiedByUser = true,
                                availability = ContentBindingAvailability.AVAILABLE,
                                runtimePayload = MihonContentBindingPayloadCodec.encode(
                                    MihonContentBindingPayload(
                                        sourceId = sourceB.id,
                                        mihonMangaId = sourceMangas.getValue(sourceB.id).id,
                                        sourceUrl = sourceMangas.getValue(sourceB.id).url,
                                        language = sourceB.lang,
                                    ),
                                ),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        preferences.upsert(
                            ContentPreference(
                                canonicalTitleId = titleId,
                                preferredAddonId = addonA,
                                preferredLanguage = sourceA.lang,
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
                        dispatcher = dispatcher,
                        sourceA = sourceA,
                        sourceB = sourceB,
                        addonA = addonA,
                        addonB = addonB,
                        title = title,
                        chapter = chapter,
                        preferences = preferences,
                        reading = CanonicalReadingRepositoryImpl(database),
                        originalReaderMode = originalReaderMode,
                        installedExtensions = installedExtensions,
                        priorExtensions = priorExtensions,
                    )
                } catch (error: Throwable) {
                    installedExtensions.value = priorExtensions
                    server.close()
                    driver.close()
                    throw error
                }
            }
        }
    }

    private data class FixtureBehavior(
        val pageCount: Int,
        val inventoryStatus: Int = 200,
        val pageListStatus: Int = 200,
        val responseDelayMillis: Long = 0,
        val imageColor: Int = Color.RED,
    )

    private class ReaderFixtureDispatcher(behaviors: Map<String, FixtureBehavior>) : Dispatcher() {
        private val behaviors = java.util.concurrent.ConcurrentHashMap(behaviors)
        private val pageRequestCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        fun setBehavior(token: String, behavior: FixtureBehavior) {
            behaviors[token] = behavior
        }

        fun pageRequestCount(token: String): Int = pageRequestCounts[token]?.get() ?: 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            if (path.startsWith("/reader/")) {
                val token = path.removePrefix("/reader/").substringBefore('/')
                val behavior = behaviors[token] ?: return MockResponse.Builder().code(404).body("unknown fixture source").build()
                if (behavior.responseDelayMillis > 0) Thread.sleep(behavior.responseDelayMillis)
                if (path == "/reader/$token/manga") {
                    if (behavior.inventoryStatus != 200) return errorResponse(behavior.inventoryStatus)
                    return textResponse("/reader/$token/chapter-1\tChapter 1\t1\tSynthetic group")
                }
                if (path == "/reader/$token/chapter-1") {
                    pageRequestCounts.computeIfAbsent(token) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                    if (behavior.pageListStatus != 200) return errorResponse(behavior.pageListStatus)
                    val pages = (0 until behavior.pageCount).joinToString("\n") { "/image/$token/$it.png" }
                    return textResponse(pages)
                }
            }
            if (path.startsWith("/image/")) {
                val token = path.removePrefix("/image/").substringBefore('/')
                val color = behaviors[token]?.imageColor ?: Color.BLACK
                return imageResponse(color)
            }
            return MockResponse.Builder().code(404).body("fixture route missing").build()
        }

        private fun textResponse(body: String) = MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/plain; charset=utf-8")
            .body(body)
            .build()

        private fun errorResponse(status: Int) = MockResponse.Builder()
            .code(status)
            .addHeader("Content-Type", "text/plain; charset=utf-8")
            .body("fixture failure")
            .build()

        private fun imageResponse(color: Int): MockResponse {
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
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
        val token: String,
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

private fun <T> awaitSourceSwitchFixtureValue(description: String, query: () -> T?): T {
    val deadline = SystemClock.elapsedRealtime() + 60_000L
    while (SystemClock.elapsedRealtime() < deadline) {
        query()?.let { return it }
        SystemClock.sleep(100L)
    }
    throw AssertionError("Timed out waiting for $description")
}
