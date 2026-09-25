package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReaderPreferenceRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentPreferenceRepositoryImpl
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real Reader selector and MainActivity title/binding-sheet navigation offline. */
@RunWith(AndroidJUnit4::class)
class CanonicalTitleSourceNavigationInstrumentedTest {

    @Test(timeout = 180_000L)
    fun coldReaderDiscoveryOpensCanonicalTitleBindingSheetOnce() {
        requireOptIn()
        withFixture { fixture ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

            fixture.launchReader(clearTask = true)
            val originalReader = awaitActivity(ReaderActivity::class.java)
            assertReaderChapter(originalReader, fixture, "COLD_READER")
            tapFindOrAddSource(device)
            val main = awaitMainActivity("COLD_READER")
            assertCanonicalRoute(main.intent, fixture, "COLD_READER")
            assertTitleAndSingleBindingSheet(device, fixture, "COLD_READER")

            val recreated = recreate(main)
            assertNotSame(main, recreated)
            assertTitleAndSingleBindingSheet(device, fixture, "COLD_READER")

            closeBindingSheet(device, fixture)
            val afterClose = recreate(recreated)
            assertNotSame(recreated, afterClose)
            assertNoBindingSheet(device, fixture)
            returnToReader(device, originalReader, fixture, "COLD_READER")
            fixture.assertUnchanged()
            report("COLD_READER", "CREATED")
        }
    }

    @Test(timeout = 180_000L)
    fun warmReaderDiscoveryReusesMainActivityAndOpensCanonicalTitleBindingSheetOnce() {
        requireOptIn()
        withFixture { fixture ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            fixture.launchMainRoot()
            val originalMain = awaitActivity(MainActivity::class.java)
            val incomingIntent = AtomicReference<Intent>()
            originalMain.addOnNewIntentListener { intent -> incomingIntent.set(intent) }

            fixture.launchReader(clearTask = false)
            val originalReader = awaitActivity(ReaderActivity::class.java)
            assertReaderChapter(originalReader, fixture, "WARM_READER")
            tapFindOrAddSource(device)
            val main = awaitActivity(MainActivity::class.java)
            reportObservation("WARM_READER", "MAIN_ACTIVITY", if (main === originalMain) "REUSED" else "REPLACED")
            assertSame("The existing MainActivity should be reused", originalMain, main)
            val routeIntent = try {
                awaitValue("MainActivity discovery intent") { incomingIntent.get() }
            } catch (error: AssertionError) {
                reportObservation("WARM_READER", "ROUTE_INTENT", "ACTION_MISSING")
                throw error
            }
            assertCanonicalRoute(routeIntent, fixture, "WARM_READER")
            assertTitleAndSingleBindingSheet(device, fixture, "WARM_READER")

            val recreated = recreate(main)
            assertNotSame(main, recreated)
            assertTitleAndSingleBindingSheet(device, fixture, "WARM_READER")

            closeBindingSheet(device, fixture)
            val afterClose = recreate(recreated)
            assertNotSame(recreated, afterClose)
            assertNoBindingSheet(device, fixture)
            returnToReader(device, originalReader, fixture, "WARM_READER")
            fixture.assertUnchanged()
            report("WARM_READER", "REUSED")
        }
    }

    @Test(timeout = 120_000L)
    fun invalidDiscoveryIntentDoesNotOpenTitleOrMutateProgressAndPreferences() {
        requireOptIn()
        withFixture { fixture ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val invalidIntent = Intent(fixture.context, MainActivity::class.java)
                .setAction(ACTION_FIND_OR_ADD_READING_SOURCE)
                .putExtra(EXTRA_CANONICAL_TITLE_ID, " \t ")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            fixture.context.startActivity(invalidIntent)

            val main = awaitActivity(MainActivity::class.java)
            assertEquals(ACTION_FIND_OR_ADD_READING_SOURCE, main.intent.action)
            assertEquals(" \t ", main.intent.getStringExtra(EXTRA_CANONICAL_TITLE_ID))
            val titleHidden = device.wait(Until.gone(By.text(fixture.title.displayTitle)), UI_TIMEOUT_MS)
            reportObservation("INVALID_INTENT", "CANONICAL_TITLE", if (titleHidden) "HIDDEN" else "VISIBLE")
            assertTrue("Invalid canonical discovery must not navigate to its seeded title", titleHidden)
            val sheetHidden = device.wait(Until.gone(By.text(BINDING_SHEET_CLOSE_LABEL)), UI_TIMEOUT_MS)
            reportObservation("INVALID_INTENT", "BINDING_SHEET", if (sheetHidden) "CLOSED" else "OPEN")
            assertTrue("Invalid canonical discovery must not open a binding sheet", sheetHidden)
            fixture.assertUnchanged()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "ANDROID_NAVIGATION|scenario=INVALID_INTENT|outcome=PASS"
                            + "|route=REJECTED|sheetCount=0|progress=UNCHANGED|preferences=UNCHANGED",
                    )
                },
            )
        }
    }

    private fun requireOptIn() {
        assertEquals(
            "This test may run only through the isolated opt-in Android navigation workflow",
            "true",
            InstrumentationRegistry.getArguments().getString("androidNavigationOptIn"),
        )
        assertEquals(
            "The destructive test fixture is restricted to the dedicated debug emulator package",
            EXPECTED_TARGET_PACKAGE,
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }

    private fun withFixture(block: (NavigationFixture) -> Unit) {
        val fixture = NavigationFixture.create()
        try {
            block(fixture)
        } finally {
            try {
                finishActivities()
            } finally {
                fixture.cleanup()
            }
        }
    }

    private fun tapFindOrAddSource(device: UiDevice) {
        val findSource = waitForObject(device, FIND_OR_ADD_SOURCE_LABEL)
        findSource.click()
    }

    private fun awaitMainActivity(scenario: String): MainActivity = try {
        awaitActivity(MainActivity::class.java).also {
            reportObservation(scenario, "MAIN_ACTIVITY", if (scenario == "COLD_READER") "CREATED" else "REUSED")
        }
    } catch (error: AssertionError) {
        reportObservation(scenario, "MAIN_ACTIVITY", "MISSING")
        throw error
    }

    private fun assertCanonicalRoute(intent: Intent, fixture: NavigationFixture, scenario: String) {
        val action = intent.action
        val canonicalTitleId = intent.getStringExtra(EXTRA_CANONICAL_TITLE_ID)
        val outcome = when {
            action != ACTION_FIND_OR_ADD_READING_SOURCE -> "ACTION_MISSING"
            canonicalTitleId != fixture.title.id -> "IDENTITY_MISMATCH"
            else -> "PASS"
        }
        reportObservation(scenario, "ROUTE_INTENT", outcome)
        assertEquals(ACTION_FIND_OR_ADD_READING_SOURCE, action)
        assertEquals(fixture.title.id, canonicalTitleId)
    }

    private fun assertTitleAndSingleBindingSheet(device: UiDevice, fixture: NavigationFixture, scenario: String) {
        val titleVisible = device.wait(Until.hasObject(By.text(fixture.title.displayTitle)), UI_TIMEOUT_MS)
        reportObservation(scenario, "CANONICAL_TITLE", if (titleVisible) "VISIBLE" else "MISSING")
        assertTrue("The canonical title screen must show the seeded identity", titleVisible)
        val closeButton = device.wait(Until.hasObject(By.text(BINDING_SHEET_CLOSE_LABEL)), UI_TIMEOUT_MS)
        val closeButtonCount = if (closeButton) device.findObjects(By.text(BINDING_SHEET_CLOSE_LABEL)).size else 0
        val sheetOutcome = when (closeButtonCount) {
            1 -> "ONE"
            0 -> "MISSING"
            else -> "DUPLICATE"
        }
        reportObservation(scenario, "BINDING_SHEET", sheetOutcome)
        assertEquals(1, closeButtonCount)
    }

    private fun closeBindingSheet(device: UiDevice, fixture: NavigationFixture) {
        waitForObject(device, BINDING_SHEET_CLOSE_LABEL).click()
        waitForObject(device, fixture.title.displayTitle)
        assertTrue(
            "The binding sheet should close and leave the canonical title visible",
            device.wait(Until.gone(By.text(BINDING_SHEET_CLOSE_LABEL)), UI_TIMEOUT_MS),
        )
    }

    private fun assertNoBindingSheet(device: UiDevice, fixture: NavigationFixture) {
        waitForObject(device, fixture.title.displayTitle)
        assertTrue(
            "Activity recreation must not reopen the dismissed binding sheet",
            device.wait(Until.gone(By.text(BINDING_SHEET_CLOSE_LABEL)), UI_TIMEOUT_MS),
        )
    }

    private fun assertReaderChapter(reader: ReaderActivity, fixture: NavigationFixture, scenario: String) {
        val chapterId = reader.intent.getStringExtra("canonical_chapter")
        reportObservation(scenario, "READER_CHAPTER", if (chapterId == fixture.chapter.id) "MATCH" else "MISMATCH")
        assertEquals("Reader must retain the canonical chapter identity", fixture.chapter.id, chapterId)
    }

    private fun returnToReader(
        device: UiDevice,
        originalReader: ReaderActivity,
        fixture: NavigationFixture,
        scenario: String,
    ) {
        device.pressBack()
        val reader = try {
            awaitActivity(ReaderActivity::class.java)
        } catch (error: AssertionError) {
            reportObservation(scenario, "READER_ACTIVITY", "MISSING")
            throw error
        }
        reportObservation(scenario, "READER_ACTIVITY", if (reader === originalReader) "ORIGINAL" else "RECREATED")
        assertSame("System Back should resume the original Reader activity instance", originalReader, reader)
        assertReaderChapter(reader, fixture, scenario)
    }

    private fun waitForObject(device: UiDevice, text: String) =
        device.wait(Until.findObject(By.text(text)), UI_TIMEOUT_MS)
            ?: throw AssertionError("Timed out waiting for expected navigation UI")

    private fun <T : Activity> awaitActivity(type: Class<T>): T = awaitValue("resumed activity") {
        var match: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            match = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance(type)
                .firstOrNull()
        }
        match
    }

    private fun <T : Activity> recreate(activity: T): T {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::recreate)
        return awaitValue("recreated activity") {
            val current = currentActivity(activity.javaClass)
            current?.takeIf { it !== activity }
        }
    }

    private fun <T : Activity> currentActivity(type: Class<T>): T? {
        var match: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            match = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance(type)
                .firstOrNull()
        }
        return match
    }

    private fun <T> awaitValue(description: String, query: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            query()?.let { return it }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun finishActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
                .flatMap(monitor::getActivitiesInStage)
                .distinct()
                .forEach(Activity::finish)
        }
        SystemClock.sleep(250L)
    }

    private fun report(scenario: String, mainActivity: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "ANDROID_NAVIGATION|scenario=$scenario|outcome=PASS"
                        + "|mainActivity=$mainActivity|identity=CANONICAL"
                        + "|sheetCount=1|recreation=PASS|closed=PASS"
                        + "|return=READER|readerActivity=ORIGINAL|readerChapter=CANONICAL"
                        + "|progress=UNCHANGED|preferences=UNCHANGED",
                )
            },
        )
    }

    private fun reportObservation(scenario: String, checkpoint: String, result: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "ANDROID_NAVIGATION_OBSERVATION|scenario=$scenario|checkpoint=$checkpoint|result=$result",
                )
            },
        )
    }

    private class NavigationFixture private constructor(
        val context: android.content.Context,
        private val driver: SqlDriver,
        private val database: Database,
        val title: CanonicalTitle,
        val chapter: CanonicalChapter,
        private val contentPreferences: ContentPreferenceRepositoryImpl,
        private val readingPreferences: CanonicalReaderPreferenceRepositoryImpl,
        private val readingProgress: CanonicalReadingRepositoryImpl,
        private val app: App,
        private val before: UserState,
    ) {

        fun launchMainRoot() {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            awaitActivity(MainActivity::class.java)
        }

        fun launchReader(clearTask: Boolean) {
            val intent = ReaderActivity.newCanonicalIntent(context, chapter.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            waitForObject(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()), FIND_OR_ADD_SOURCE_LABEL)
        }

        fun assertUnchanged() {
            val after = runBlocking { snapshot() }
            assertEquals("Canonical progress/preferences or source preferences changed", before, after)
        }

        private suspend fun snapshot() = UserState(
            contentPreference = contentPreferences.get(title.id),
            readerPreference = readingPreferences.get(title.id),
            progress = readingProgress.getProgress(chapter.id),
            history = readingProgress.getHistory(chapter.id),
            automaticFallback = app.graph.canonicalReaderPreferences.automaticFallback.get(),
            preferredLanguages = app.graph.canonicalReaderPreferences.preferredLanguages.get().sorted(),
            disabledSources = app.graph.sourcePreferences.disabledSources.get().sorted(),
            enabledLanguages = app.graph.sourcePreferences.enabledLanguages.get().sorted(),
        )

        fun cleanup() {
            try {
                runBlocking {
                    database.tsuzuki_titlesQueries.deleteTsuzukiTitle(title.id)
                    assertEquals(null, CanonicalTitleRepositoryImpl(database).getById(title.id))
                    assertEquals(null, CanonicalChapterRepositoryImpl(database).getById(chapter.id))
                    assertEquals(null, readingProgress.getProgress(chapter.id))
                    assertEquals(null, contentPreferences.get(title.id))
                    assertEquals(null, readingPreferences.get(title.id))
                }
            } finally {
                driver.close()
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                1,
                Bundle().apply { putString("stream", "ANDROID_NAVIGATION_CLEANUP|fixtureRows=DELETED") },
            )
        }

        companion object {
            fun create(): NavigationFixture {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                assertEquals(EXPECTED_TARGET_PACKAGE, context.packageName)
                val app = context.applicationContext as App
                assertTrue(
                    "The isolated navigation fixture must not query installed reading Add-ons",
                    runBlocking { app.graph.addonRepository.snapshot() }.isEmpty(),
                )

                val titleId = "android-navigation-${UUID.randomUUID()}"
                val chapterId = "$titleId-chapter-1"
                val now = System.currentTimeMillis()
                val title = CanonicalTitle(
                    id = titleId,
                    displayTitle = "Android Navigation Fixture ${UUID.randomUUID().toString().take(8)}",
                    identityState = CanonicalIdentityState.SOURCE_ONLY,
                    createdAt = now,
                    updatedAt = now,
                )
                val chapter = CanonicalChapter(
                    id = chapterId,
                    canonicalTitleId = title.id,
                    displayNumber = "1",
                    type = CanonicalChapterType.REGULAR,
                    baseNumber = 1,
                    confidence = 1.0,
                    createdAt = now,
                    updatedAt = now,
                )
                val driver = AppBindings.providesSqlDriver(context)
                try {
                    driver.execute(null, "SELECT 1", 0)
                    val database = AppBindings.providesDatabase(driver)
                    val titles = CanonicalTitleRepositoryImpl(database)
                    val chapters = CanonicalChapterRepositoryImpl(database)
                    val contentPreferences = ContentPreferenceRepositoryImpl(database)
                    val readingPreferences = CanonicalReaderPreferenceRepositoryImpl(database)
                    val readingProgress = CanonicalReadingRepositoryImpl(database)
                    runBlocking {
                        assertEquals(null, titles.getById(title.id))
                        titles.insert(title)
                        chapters.upsert(chapter)
                        contentPreferences.upsert(
                            ContentPreference(
                                canonicalTitleId = title.id,
                                preferredAddonId = AddonId("navigation-sentinel"),
                                preferredLanguage = "en",
                                updatedAt = now,
                            ),
                        )
                        readingPreferences.upsert(
                            CanonicalReaderPreference(
                                canonicalTitleId = title.id,
                                automaticFallback = true,
                                updatedAt = now,
                            ),
                        )
                        readingProgress.upsertProgress(
                            CanonicalChapterProgress(
                                canonicalChapterId = chapter.id,
                                read = false,
                                lastPageRead = 7L,
                                updatedAt = now,
                            ),
                        )
                    }
                    val before = runBlocking {
                        UserState(
                            contentPreference = contentPreferences.get(title.id),
                            readerPreference = readingPreferences.get(title.id),
                            progress = readingProgress.getProgress(chapter.id),
                            history = readingProgress.getHistory(chapter.id),
                            automaticFallback = app.graph.canonicalReaderPreferences.automaticFallback.get(),
                            preferredLanguages = app.graph.canonicalReaderPreferences.preferredLanguages.get().sorted(),
                            disabledSources = app.graph.sourcePreferences.disabledSources.get().sorted(),
                            enabledLanguages = app.graph.sourcePreferences.enabledLanguages.get().sorted(),
                        )
                    }
                    return NavigationFixture(
                        context = context,
                        driver = driver,
                        database = database,
                        title = title,
                        chapter = chapter,
                        contentPreferences = contentPreferences,
                        readingPreferences = readingPreferences,
                        readingProgress = readingProgress,
                        app = app,
                        before = before,
                    )
                } catch (error: Throwable) {
                    try {
                        val database = AppBindings.providesDatabase(driver)
                        runBlocking { database.tsuzuki_titlesQueries.deleteTsuzukiTitle(title.id) }
                    } finally {
                        driver.close()
                    }
                    throw error
                }
            }
        }

    }

    private data class UserState(
        val contentPreference: ContentPreference?,
        val readerPreference: CanonicalReaderPreference?,
        val progress: CanonicalChapterProgress?,
        val history: tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory?,
        val automaticFallback: Boolean,
        val preferredLanguages: List<String>,
        val disabledSources: List<String>,
        val enabledLanguages: List<String>,
    )

    private companion object {
        const val EXPECTED_TARGET_PACKAGE = "app.mihon.dev"
        const val ACTION_FIND_OR_ADD_READING_SOURCE =
            "eu.kanade.tachiyomi.internal.FIND_OR_ADD_READING_SOURCE"
        const val EXTRA_CANONICAL_TITLE_ID = "eu.kanade.tachiyomi.internal.CANONICAL_TITLE_ID"
        const val FIND_OR_ADD_SOURCE_LABEL = "Find or add reading source"
        const val BINDING_SHEET_CLOSE_LABEL = "Done"
        const val UI_TIMEOUT_MS = 60_000L
    }
}
