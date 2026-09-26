package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
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
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            val positionBefore = advanceToPage(reader, fixture, 4, "SOURCE_A_TO_B")
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "SOURCE_A_TO_B")
            val progressBefore = awaitValue("canonical progress for observed page 4") {
                runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
                    ?.takeIf { it.lastPageRead >= positionBefore.toLong() }
            }
            val preferenceBefore = runBlocking { fixture.preferences.get(fixture.title.id) }
            assertEquals(fixture.addonA, preferenceBefore?.preferredAddonId)

            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            val after = awaitReaderPages(reader, fixture.sourceB.name, expectedCount = 10)
            assertEquals(
                "An equal-length source switch must preserve the observed page index",
                positionBefore,
                after.first,
            )
            assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
            confirmPreferredSource(fixture, fixture.addonB)
            awaitImagePixels(reader, fixture, Color.rgb(35, 70, 225), "SOURCE_A_TO_B")

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
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "EMPTY_OR_FAILING")
            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            awaitSourceRequest(fixture.dispatcher, fixture.sourceB.token, emptyPagesBefore)
            assertReaderSession(reader, fixture, fixture.sourceA.name, initial.first, 10)
            dismissSourceSelectorThroughReaderUi()
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "EMPTY_OR_FAILING")

            fixture.dispatcher.setBehavior(fixture.sourceB.token, FixtureBehavior(pageCount = 10, pageListStatus = 503))
            val failedPagesBefore = fixture.dispatcher.pageRequestCount(fixture.sourceB.token)
            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            awaitSourceRequest(fixture.dispatcher, fixture.sourceB.token, failedPagesBefore)
            assertReaderSession(reader, fixture, fixture.sourceA.name, initial.first, 10)
            assertEquals(
                "A failed replacement must not change the source preference",
                fixture.addonA,
                runBlocking {
                    fixture.preferences.get(fixture.title.id)?.preferredAddonId
                },
            )
            assertEquals(
                "A failed replacement must not change canonical progress",
                progressBefore,
                runBlocking {
                    fixture.reading.getProgress(fixture.chapter.id)
                },
            )
            assertEquals(
                "A failed replacement must not record history",
                historyBefore,
                runBlocking {
                    fixture.reading.getHistory(fixture.chapter.id)
                },
            )
            dismissSourceSelectorThroughReaderUi()
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "EMPTY_OR_FAILING")
            assertSame(
                "Failure must not replace the published chapter object",
                retiredCandidate.chapter,
                reader.viewModel.state.value.currentChapter!!.pages!![initial.first].chapter,
            )
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
            val positionBefore = advanceToPage(reader, fixture, 8, "PAGE_COUNT_CLAMP")
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "PAGE_COUNT_CLAMP")
            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            val after = awaitReaderPages(reader, fixture.sourceB.name, 3)
            confirmPreferredSource(fixture, fixture.addonB)
            awaitImagePixels(reader, fixture, Color.rgb(35, 70, 225), "PAGE_COUNT_CLAMP")
            assertTrue(
                "The published page index must be valid for the shorter source",
                after.first in 0 until after.second,
            )
            assertEquals(
                "A shorter source must clamp the prior index",
                minOf(positionBefore, after.second - 1),
                after.first,
            )
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
            advanceToPage(reader, fixture, 3, "RETIRED_CALLBACK")
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "RETIRED_CALLBACK")
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
                assertEquals(
                    "Old callback must not change active page position",
                    sourceB.first,
                    reader.viewModel.state.value.currentPage - 1,
                )
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

    @Test(timeout = 240_000L)
    fun activityRecreationRestoresObservedCanonicalPosition() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val original = awaitActivity(ReaderActivity::class.java)
            awaitReaderPages(original, fixture.sourceA.name, expectedCount = 10)
            val positionBefore = advanceToPage(original, fixture, 6, "ACTIVITY_RECREATE")
            awaitImagePixels(original, fixture, Color.rgb(220, 40, 40), "ACTIVITY_RECREATE")

            chooseSourceThroughReaderUi(original, fixture.sourceB.name)
            val switched = awaitReaderPages(original, fixture.sourceB.name, expectedCount = 10)
            assertEquals(positionBefore, switched.first)
            confirmPreferredSource(fixture, fixture.addonB)
            awaitImagePixels(original, fixture, Color.rgb(35, 70, 225), "ACTIVITY_RECREATE")

            InstrumentationRegistry.getInstrumentation().runOnMainSync { original.recreate() }
            val recreated = awaitReplacementReaderActivity(original)
            assertNotSame("Activity recreation must produce a new Activity instance", original, recreated)
            val restored = awaitReaderPages(recreated, fixture.sourceB.name, expectedCount = 10)
            assertEquals("Recreated Reader must restore the observed page position", positionBefore, restored.first)
            assertEquals(fixture.chapter.id, recreated.intent.getStringExtra("canonical_chapter"))
            awaitImagePixels(recreated, fixture, Color.rgb(35, 70, 225), "ACTIVITY_RECREATE")
            assertEquals(fixture.addonB, runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId })
            assertEquals(
                fixture.chapter.id,
                runBlocking { fixture.reading.getHistory(fixture.chapter.id)?.canonicalChapterId },
            )
            report(
                scenario = "ACTIVITY_RECREATE",
                pageCount = restored.second,
                positionIndex = restored.first,
                details = "sourceAPageCount=10|sourceBPageCount=${restored.second}" +
                    "|positionBefore=$positionBefore|positionAfter=${restored.first}",
            )
        }
    }

    @Test(timeout = 240_000L)
    fun repeatedSourceSwitchKeepsPreferenceAndSingleHistoryEntry() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
            advanceToPage(reader, fixture, 2, "HISTORY_IDEMPOTENCE")
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "HISTORY_IDEMPOTENCE")

            chooseSourceThroughReaderUi(reader, fixture.sourceB.name)
            val firstSwitch = awaitReaderPages(reader, fixture.sourceB.name, expectedCount = 10)
            confirmPreferredSource(fixture, fixture.addonB)
            awaitImagePixels(reader, fixture, Color.rgb(35, 70, 225), "HISTORY_IDEMPOTENCE")

            chooseSourceThroughReaderUi(reader, fixture.sourceA.name)
            val secondSwitch = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
            confirmPreferredSource(fixture, fixture.addonA)
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "HISTORY_IDEMPOTENCE")

            val preference = runBlocking { fixture.preferences.get(fixture.title.id) }
            assertEquals(
                "Only the successfully confirmed Add-on becomes preferred",
                fixture.addonA,
                preference?.preferredAddonId,
            )
            val history = runBlocking { fixture.reading.getHistory(fixture.chapter.id) }
            assertEquals(
                "Switching must preserve history under the canonical chapter",
                fixture.chapter.id,
                history?.canonicalChapterId,
            )
            assertEquals(
                "Source switches must not duplicate canonical history rows",
                1L,
                fixture.canonicalHistoryRowCount(),
            )
            assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
            report(
                scenario = "HISTORY_IDEMPOTENCE",
                pageCount = secondSwitch.second,
                positionIndex = secondSwitch.first,
                details = "sourceAPageCount=10|sourceBPageCount=${firstSwitch.second}" +
                    "|positionBefore=${firstSwitch.first}|positionAfter=${secondSwitch.first}|historyRows=1",
            )
        }
    }

    @Test(timeout = 240_000L)
    fun slowSourceDoesNotBlockHealthySourceOption() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val current = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "SLOW_TO_HEALTHY")
            val sourceARoutesBeforeDiscovery = fixture.dispatcher.routeCounts(fixture.sourceA.token)
            val sourceBRoutesBeforeDiscovery = fixture.dispatcher.routeCounts(fixture.sourceB.token)
            fixture.dispatcher.holdResponses(fixture.sourceB.token)
            try {
                openSourceSelectorThroughReaderUi(reader)
                awaitValue("slow source request to remain in flight") {
                    fixture.dispatcher.heldRequestCount(fixture.sourceB.token).takeIf { it > 0 }
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val healthySourceVisible = device.wait(
                    Until.hasObject(By.text(fixture.sourceA.name)),
                    8_000L,
                )
                val selectorSnapshot = when {
                    healthySourceVisible -> "READY_WITH_A"
                    device.hasObject(By.textContains("Finding available chapters")) ||
                        device.hasObject(By.text("Stop searching")) -> "DISCOVERING"
                    device.hasObject(By.text("Choose reading source")) -> "OPEN_WITHOUT_A"
                    else -> "CLOSED_OR_OTHER"
                }
                val typedSelector = typedSelectorSnapshot(reader, fixture)
                val routeSummary = reportDiscoveryDiagnostic(
                    typedSelector = typedSelector,
                    fixture = fixture,
                    sourceARoutesBefore = sourceARoutesBeforeDiscovery,
                    sourceBRoutesBefore = sourceBRoutesBeforeDiscovery,
                )
                assertTrue(
                    "A healthy source option must appear while another source response is still pending " +
                        "(selector=$selectorSnapshot; state=${typedSelector.state}; $routeSummary)",
                    healthySourceVisible,
                )
                assertTrue(
                    "The slow synthetic source must still be pending when the healthy option is visible",
                    fixture.dispatcher.heldRequestCount(fixture.sourceB.token) > 0,
                )
                assertEquals(
                    "Automatic discovery must not change the source preference",
                    fixture.addonA,
                    runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId },
                )
                val unchanged = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
                assertEquals(current.first, unchanged.first)
                assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
                dismissSourceSelectorThroughReaderUi()
                awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "SLOW_TO_HEALTHY")
                report(
                    scenario = "SLOW_TO_HEALTHY",
                    pageCount = unchanged.second,
                    positionIndex = unchanged.first,
                    details = "sourceAHealthy=true|sourceBPending=true|session=PREVIOUS_PRESERVED",
                )
            } finally {
                fixture.dispatcher.releaseResponses(fixture.sourceB.token)
            }
        }
    }

    @Test(timeout = 240_000L)
    fun cancelledDiscoveryCannotMutateActiveReaderSession() {
        requireOptIn()
        withFixture { fixture ->
            fixture.launchReader()
            val reader = awaitActivity(ReaderActivity::class.java)
            val initial = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
            val positionBefore = advanceToPage(reader, fixture, 3, "DISCOVERY_CANCEL")
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "DISCOVERY_CANCEL")
            fixture.dispatcher.holdResponses(fixture.sourceA.token)
            fixture.dispatcher.holdResponses(fixture.sourceB.token)
            try {
                openSourceSelectorThroughReaderUi(reader)
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(
                    "Visible automatic discovery must offer a cancel action",
                    device.wait(Until.hasObject(By.text("Stop searching")), UI_TIMEOUT_MS),
                )
                assertTrue(
                    "At least one extension-backed response must still be pending at cancellation",
                    fixture.dispatcher.heldRequestCount(fixture.sourceA.token) +
                        fixture.dispatcher.heldRequestCount(fixture.sourceB.token) > 0,
                )
                clickText(device, "Stop searching")
                assertTrue(
                    "Cancelled discovery must stop showing its in-progress action",
                    device.wait(Until.gone(By.text("Stop searching")), UI_TIMEOUT_MS),
                )
                fixture.dispatcher.releaseResponses(fixture.sourceA.token)
                fixture.dispatcher.releaseResponses(fixture.sourceB.token)
                SystemClock.sleep(1_000L)
                val unchanged = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
                assertEquals(positionBefore, unchanged.first)
                assertEquals(fixture.chapter.id, reader.intent.getStringExtra("canonical_chapter"))
                assertEquals(
                    fixture.addonA,
                    runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId },
                )
                dismissSourceSelectorThroughReaderUi()
                awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "DISCOVERY_CANCEL")
                assertEquals(
                    "The previous page position must remain observable after late responses return",
                    initial.second,
                    unchanged.second,
                )
                report(
                    scenario = "DISCOVERY_CANCEL",
                    pageCount = unchanged.second,
                    positionIndex = unchanged.first,
                    details = "cancelled=true|lateResponsesReleased=true|session=PREVIOUS_PRESERVED",
                )
            } finally {
                fixture.dispatcher.releaseResponses(fixture.sourceA.token)
                fixture.dispatcher.releaseResponses(fixture.sourceB.token)
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
    ): Pair<Int, Int> {
        var lastSnapshot: ReaderViewSnapshot? = null
        return try {
            awaitValue("loaded Reader pages for $expectedSource and an initialized pager") {
                val snapshot = readerViewSnapshot(reader)
                lastSnapshot = snapshot
                val state = reader.viewModel.state.value
                val chapter = state.currentChapter ?: return@awaitValue null
                val pages = chapter.pages ?: return@awaitValue null
                if (state.source?.name != expectedSource || pages.size != expectedCount) return@awaitValue null
                val index = state.currentPage - 1
                if (
                    index !in pages.indices || pages[index].status != Page.State.Ready ||
                    pages[index].stream == null || chapter.state !is ReaderChapter.State.Loaded
                ) {
                    return@awaitValue null
                }
                val pager = snapshot.pager
                if (
                    !pager.visible || pager.count < expectedCount ||
                    pager.currentItem !in 0 until pager.count || !pager.idle
                ) {
                    return@awaitValue null
                }
                index to pages.size
            }
        } catch (error: AssertionError) {
            val snapshot = lastSnapshot ?: runCatching { readerViewSnapshot(reader) }.getOrNull()
            snapshot?.let {
                reportReaderViewDiagnostic(
                    scenario = "PAGER_READINESS",
                    phase = "PAGER_WAIT_TIMEOUT",
                    snapshot = it,
                    interactionInjected = false,
                    interactionTarget = if (it.viewer == "NONE") "NONE" else "READER_PAGER",
                )
            }
            throw AssertionError(
                "Reader pages or pager did not reach a usable state " +
                    "(expectedPageCount=$expectedCount, viewer=${snapshot?.viewer}, " +
                    "pagesLoaded=${snapshot?.pagesLoaded}, pageCount=${snapshot?.pageCount}, " +
                    "pageState=${snapshot?.pageState}, position=${snapshot?.position}, " +
                    "pagerVisible=${snapshot?.pager?.visible}, pagerCount=${snapshot?.pager?.count}, " +
                    "pagerCurrentItem=${snapshot?.pager?.currentItem}, pagerIdle=${snapshot?.pager?.idle})",
                error,
            )
        }
    }

    private fun awaitImagePixels(
        reader: ReaderActivity,
        fixture: SourceSwitchFixture,
        expectedColor: Int,
        scenario: String,
    ) {
        // Only the disposable fixture opts out of secure capture; production privacy is unchanged.
        awaitValue("Reader test window to permit visual screenshot evidence") {
            if ((reader.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) == 0) {
                true
            } else {
                null
            }
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var lastMatchingSampleCount = 0
        var lastMatchingRowCount = 0
        val imageEvidence = try {
            awaitValue("Reader screenshot to contain the synthetic page color") {
                val screenshot = device.takeScreenshot() ?: return@awaitValue null
                try {
                    val evidence = countImageColorSamples(screenshot, expectedColor)
                    lastMatchingSampleCount = evidence.sampleCount
                    lastMatchingRowCount = evidence.rowsWithLongRun
                    evidence.takeIf {
                        it.sampleCount >= MIN_IMAGE_COLOR_SAMPLES &&
                            it.rowsWithLongRun >= MIN_IMAGE_COLOR_ROWS
                    }
                } finally {
                    screenshot.recycle()
                }
            }
        } catch (error: AssertionError) {
            val snapshot = reportScreenshotFailure(
                reader = reader,
                fixture = fixture,
                scenario = scenario,
                expectedColor = expectedColor,
                matchingSamples = lastMatchingSampleCount,
                matchingRows = lastMatchingRowCount,
            )
            throw AssertionError(
                "Reader screenshot lacked a rendered synthetic image region " +
                    "(matchingSamples=$lastMatchingSampleCount, rows=$lastMatchingRowCount, " +
                    "viewer=${snapshot?.viewer}, position=${snapshot?.position}, " +
                    "pageState=${snapshot?.pageState}, stream=${snapshot?.streamPresent})",
                error,
            )
        } catch (error: RuntimeException) {
            reportScreenshotFailure(
                reader = reader,
                fixture = fixture,
                scenario = scenario,
                expectedColor = expectedColor,
                matchingSamples = lastMatchingSampleCount,
                matchingRows = lastMatchingRowCount,
            )
            throw error
        }
        assertTrue(
            "Reader screenshot must contain a visible region of the loaded fixture page color",
            imageEvidence.sampleCount >= MIN_IMAGE_COLOR_SAMPLES &&
                imageEvidence.rowsWithLongRun >= MIN_IMAGE_COLOR_ROWS,
        )
        val snapshot = readerViewSnapshot(reader)
        val pager = snapshot.pager
        val currentImageVisible =
            pager.visible && pager.holderAttached && pager.holderVisible &&
                pager.imageViewPresent && pager.imageViewVisible && pager.imageViewReady && !pager.errorVisible
        if (!currentImageVisible) {
            val imageCounts = fixture.imageRequestCounts()
            reportReaderViewDiagnostic(
                scenario = scenario,
                phase = "IMAGE_VIEW_NOT_READY",
                snapshot = snapshot,
                interactionInjected = false,
                interactionTarget = "READER_PAGER",
                expectedColor = colorName(expectedColor),
                matchingSamples = imageEvidence.sampleCount,
                matchingRows = imageEvidence.rowsWithLongRun,
                imageRequestsA = imageCounts.first,
                imageRequestsB = imageCounts.second,
            )
        }
        assertTrue(
            "The current Reader page image must be attached, visible, decoded, and free of an error view",
            currentImageVisible,
        )
    }

    private fun reportScreenshotFailure(
        reader: ReaderActivity,
        fixture: SourceSwitchFixture,
        scenario: String,
        expectedColor: Int,
        matchingSamples: Int,
        matchingRows: Int,
    ): ReaderViewSnapshot? {
        val snapshot = runCatching { readerViewSnapshot(reader) }.getOrNull() ?: return null
        val imageCounts = fixture.imageRequestCounts()
        reportReaderViewDiagnostic(
            scenario = scenario,
            phase = "SCREENSHOT_TIMEOUT",
            snapshot = snapshot,
            interactionInjected = false,
            interactionTarget = "NONE",
            expectedColor = colorName(expectedColor),
            matchingSamples = matchingSamples,
            matchingRows = matchingRows,
            imageRequestsA = imageCounts.first,
            imageRequestsB = imageCounts.second,
        )
        return snapshot
    }

    private fun countImageColorSamples(bitmap: Bitmap, expectedColor: Int): ImageColorEvidence {
        val sampleStride = 4
        val left = bitmap.width / 20
        val right = bitmap.width - left
        val top = bitmap.height / 20
        val bottom = bitmap.height - top
        var matchingSamples = 0
        var rowsWithLongRun = 0

        for (y in top until bottom step sampleStride) {
            var matchingRun = 0
            var longestRun = 0
            for (x in left until right step sampleStride) {
                if (isNearColor(bitmap.getPixel(x, y), expectedColor)) {
                    matchingSamples++
                    matchingRun++
                    longestRun = maxOf(longestRun, matchingRun)
                } else {
                    matchingRun = 0
                }
            }
            if (longestRun >= MIN_IMAGE_COLOR_RUN_SAMPLES) rowsWithLongRun++
        }
        return ImageColorEvidence(matchingSamples, rowsWithLongRun)
    }

    private fun isNearColor(actual: Int, expected: Int): Boolean =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 45 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 45 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 45

    private data class ImageColorEvidence(
        val sampleCount: Int,
        val rowsWithLongRun: Int,
    )

    private fun SourceSwitchFixture.imageRequestCounts(): Pair<Int, Int> =
        dispatcher.imageRequestCount(sourceA.token) to dispatcher.imageRequestCount(sourceB.token)

    private fun advanceToPage(
        reader: ReaderActivity,
        fixture: SourceSwitchFixture,
        requestedIndex: Int,
        scenario: String,
    ): Int {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val startIndex = readerViewSnapshot(reader).position
        assertTrue("Current Reader page index must be observable before advancing", startIndex >= 0)
        if (requestedIndex > startIndex) {
            for (expectedIndex in (startIndex + 1)..requestedIndex) {
                val before = readerViewSnapshot(reader)
                val pager = pagerSnapshot(reader)
                val tapPoint = pagerNextTapPoint(reader)
                val injected =
                    pager.visible && pager.count > 0 && tapPoint != null && device.click(tapPoint.x, tapPoint.y)
                try {
                    assertTrue("Reader pager must expose and accept a forward navigation-zone tap", injected)
                    awaitObservedPage(reader, expectedIndex)
                } catch (error: AssertionError) {
                    val after = readerViewSnapshot(reader)
                    val imageCounts = fixture.imageRequestCounts()
                    reportReaderViewDiagnostic(
                        scenario = scenario,
                        phase = "PAGE_TAP_TIMEOUT",
                        snapshot = after,
                        interactionInjected = injected,
                        interactionTarget = if (before.viewer == "NONE") "NONE" else "READER_PAGER",
                        imageRequestsA = imageCounts.first,
                        imageRequestsB = imageCounts.second,
                    )
                    throw AssertionError(
                        "Reader navigation-zone tap did not reach the requested ready page " +
                            "(requestedIndex=$expectedIndex, beforeIndex=${before.position}, " +
                            "afterIndex=${after.position}, viewer=${after.viewer}, pageState=${after.pageState}, " +
                            "pagerVisible=${after.pager.visible}, pagerCount=${after.pager.count}, " +
                            "pagerCurrentItem=${after.pager.currentItem}, pagerIdle=${after.pager.idle})",
                        error,
                    )
                }
            }
        }
        return awaitObservedPage(reader, requestedIndex)
    }

    private fun pagerNextTapPoint(reader: ReaderActivity): PagerTapPoint? {
        var tapPoint: PagerTapPoint? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewer = reader.viewModel.state.value.viewer as? PagerViewer ?: return@runOnMainSync
            val pager = viewer.pager
            val nextRegion = viewer.config.navigator.getRegions().firstOrNull {
                it.type == NavigationRegion.RIGHT || it.type == NavigationRegion.NEXT
            } ?: return@runOnMainSync
            val normalizedPoint = PointF(nextRegion.rectF.centerX(), nextRegion.rectF.centerY())
            val action = viewer.config.navigator.getAction(normalizedPoint)
            if (action != NavigationRegion.RIGHT && action != NavigationRegion.NEXT) return@runOnMainSync
            val location = IntArray(2)
            pager.getLocationOnScreen(location)
            if (pager.width > 0 && pager.height > 0) {
                tapPoint = PagerTapPoint(
                    x = location[0] + (normalizedPoint.x * pager.width).toInt(),
                    y = location[1] + (normalizedPoint.y * pager.height).toInt(),
                )
            }
        }
        return tapPoint
    }

    private fun pagerSnapshot(reader: ReaderActivity): PagerSnapshot {
        var snapshot = PagerSnapshot(
            visible = false,
            count = 0,
            currentItem = -1,
            idle = false,
            holderPresent = false,
            holderAttached = false,
            holderVisible = false,
            imageViewPresent = false,
            imageViewVisible = false,
            imageViewReady = false,
            errorVisible = false,
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewer = reader.viewModel.state.value.viewer as? PagerViewer ?: return@runOnMainSync
            val pager = viewer.pager
            val state = reader.viewModel.state.value
            val currentPage = state.currentChapter?.pages?.getOrNull(state.currentPage - 1)
            val holder = currentPage?.let { page ->
                pager.children.filterIsInstance<PagerPageHolder>().firstOrNull { it.page === page }
            }
            val imageView = holder?.children?.filterIsInstance<SubsamplingScaleImageView>()?.firstOrNull()
            val errorVisible = holder?.findViewById<View>(R.id.error_message)?.visibility == View.VISIBLE
            val idle = runCatching {
                PagerViewer::class.java.getDeclaredField("isIdle")
                    .apply { isAccessible = true }
                    .getBoolean(viewer)
            }.getOrDefault(false)
            snapshot = PagerSnapshot(
                visible = pager.visibility == View.VISIBLE,
                count = pager.adapter?.count ?: 0,
                currentItem = pager.currentItem,
                idle = idle,
                holderPresent = holder != null,
                holderAttached = holder?.isAttachedToWindow == true,
                holderVisible = holder?.isShown == true,
                imageViewPresent = imageView != null,
                imageViewVisible = imageView?.isShown == true,
                imageViewReady = imageView?.isReady == true,
                errorVisible = errorVisible,
            )
        }
        return snapshot
    }

    private fun awaitObservedPage(reader: ReaderActivity, requestedIndex: Int): Int {
        var observedIndex: Int? = null
        var observedPageCount: Int? = null
        var observedPageStatus: Page.State? = null
        return try {
            awaitValue("Reader to display requested page $requestedIndex") {
                val state = reader.viewModel.state.value
                val chapter = state.currentChapter ?: return@awaitValue null
                val pages = chapter.pages ?: return@awaitValue null
                val index = state.currentPage - 1
                observedIndex = index
                observedPageCount = pages.size
                val page = pages.getOrNull(index) ?: return@awaitValue null
                observedPageStatus = page.status
                if (index != requestedIndex || page.status != Page.State.Ready || page.stream == null) {
                    return@awaitValue null
                }
                val pager = pagerSnapshot(reader)
                if (!pager.visible || !pager.idle || pager.currentItem !in 0 until pager.count) {
                    return@awaitValue null
                }
                index
            }
        } catch (error: AssertionError) {
            throw AssertionError(
                "Timed out observing Reader page (requestedIndex=$requestedIndex, " +
                    "observedIndex=$observedIndex, pageCount=$observedPageCount, pageStatus=$observedPageStatus)",
                error,
            )
        }
    }

    private fun readerViewSnapshot(reader: ReaderActivity): ReaderViewSnapshot {
        val state = reader.viewModel.state.value
        val chapter = state.currentChapter
        val pages = chapter?.pages
        val position = (state.currentPage - 1).takeIf { it >= 0 } ?: -1
        val page = pages?.getOrNull(position)
        var focused = "UNKNOWN"
        var windowSecure = false
        var windowHasFocus = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            windowSecure =
                (reader.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            windowHasFocus = reader.window.decorView.hasWindowFocus()
            val focusedView = reader.currentFocus
            focused = when {
                focusedView == null -> "UNKNOWN"
                focusedView.hasFocus() -> "FOCUSED"
                else -> "NO_FOCUS"
            }
        }
        return ReaderViewSnapshot(
            viewer = state.viewer?.javaClass?.simpleName ?: "NONE",
            focused = focused,
            windowSecure = windowSecure,
            windowHasFocus = windowHasFocus,
            streamPresent = page?.stream != null,
            pagesLoaded = chapter?.state is ReaderChapter.State.Loaded,
            pageCount = pages?.size ?: 0,
            pageState = pageStateName(page?.status),
            position = position,
            pager = pagerSnapshot(reader),
        )
    }

    private fun pageStateName(state: Page.State?): String = when (state) {
        null -> "NONE"
        Page.State.Queue -> "QUEUE"
        Page.State.LoadPage -> "LOAD_PAGE"
        Page.State.DownloadImage -> "DOWNLOAD_IMAGE"
        Page.State.Ready -> "READY"
        is Page.State.Error -> "ERROR"
    }

    private fun colorName(color: Int): String = when (color) {
        Color.rgb(220, 40, 40) -> "RED"
        Color.rgb(35, 70, 225) -> "BLUE"
        else -> "NONE"
    }

    private fun reportReaderViewDiagnostic(
        scenario: String,
        phase: String,
        snapshot: ReaderViewSnapshot,
        interactionInjected: Boolean,
        interactionTarget: String,
        expectedColor: String = "NONE",
        matchingSamples: Int? = null,
        matchingRows: Int? = null,
        imageRequestsA: Int? = null,
        imageRequestsB: Int? = null,
    ) {
        val colorDetail = expectedColor.takeIf { it != "NONE" }?.let { "|expectedColor=$it" }.orEmpty()
        val pixelDetails = "|matchingSamples=${matchingSamples ?: 0}|matchingRows=${matchingRows ?: 0}$colorDetail"
        val imageRequests = if (imageRequestsA != null && imageRequestsB != null) {
            "|imageRequestsA=$imageRequestsA|imageRequestsB=$imageRequestsB"
        } else {
            ""
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "READER_VIEW_DIAGNOSTIC|scenario=$scenario|phase=$phase|viewer=${snapshot.viewer}" +
                        "|focused=${snapshot.focused}|stream=${snapshot.streamPresent.toWireBoolean()}" +
                        "|pagesLoaded=${snapshot.pagesLoaded.toWireBoolean()}|pageCount=${snapshot.pageCount}" +
                        "|pageState=${snapshot.pageState}|position=${snapshot.position}" +
                        "|pagerVisible=${snapshot.pager.visible.toWireBoolean()}|pagerCount=${snapshot.pager.count}" +
                        "|pagerCurrentItem=${snapshot.pager.currentItem}" +
                        "|pagerIdle=${snapshot.pager.idle.toWireBoolean()}" +
                        "|holderPresent=${snapshot.pager.holderPresent.toWireBoolean()}" +
                        "|holderAttached=${snapshot.pager.holderAttached.toWireBoolean()}" +
                        "|holderVisible=${snapshot.pager.holderVisible.toWireBoolean()}" +
                        "|imageViewPresent=${snapshot.pager.imageViewPresent.toWireBoolean()}" +
                        "|imageViewVisible=${snapshot.pager.imageViewVisible.toWireBoolean()}" +
                        "|imageViewReady=${snapshot.pager.imageViewReady.toWireBoolean()}" +
                        "|errorVisible=${snapshot.pager.errorVisible.toWireBoolean()}" +
                        "|windowSecure=${snapshot.windowSecure.toWireBoolean()}" +
                        "|windowHasFocus=${snapshot.windowHasFocus.toWireBoolean()}" +
                        "|interactionInjected=${interactionInjected.toWireBoolean()}" +
                        "|interactionTarget=$interactionTarget$pixelDetails$imageRequests",
                )
            },
        )
    }

    private fun Boolean.toWireBoolean(): String = if (this) "TRUE" else "FALSE"

    private fun Boolean.toWireDigit(): String = if (this) "1" else "0"

    private fun typedSelectorSnapshot(reader: ReaderActivity, fixture: SourceSwitchFixture): SelectorSnapshot {
        val state = runCatching {
            ReaderActivity::class.java.getDeclaredMethod("getContentSelectorViewModel")
                .apply { isAccessible = true }
                .invoke(reader)
                .let { it as ContentSelectorScreenModel }
                .state
                .value
        }.getOrNull() ?: return SelectorSnapshot("UNKNOWN", 0, false, false, false, 0)

        return when (state) {
            ContentSelectorScreenState.Loading -> SelectorSnapshot("LOADING", 0, false, false, false, 0)
            is ContentSelectorScreenState.Discovering -> SelectorSnapshot(
                "DISCOVERING",
                0,
                false,
                false,
                state.canonicalTitleId == fixture.title.id && state.canonicalChapterId == fixture.chapter.id,
                state.failedAttempts,
            )
            is ContentSelectorScreenState.Ready -> SelectorSnapshot(
                "READY",
                state.options.size,
                state.options.any {
                    it.option.addonId == fixture.addonA && it.option.canonicalChapterId == fixture.chapter.id
                },
                state.options.any {
                    it.option.addonId == fixture.addonB && it.option.canonicalChapterId == fixture.chapter.id
                },
                state.canonicalTitleId == fixture.title.id && state.canonicalChapterId == fixture.chapter.id &&
                    state.options.any { it.option.canonicalChapterId == fixture.chapter.id },
                state.failedProviderCount,
            )
            is ContentSelectorScreenState.Empty -> SelectorSnapshot(
                "EMPTY",
                0,
                false,
                false,
                state.canonicalTitleId == fixture.title.id && state.canonicalChapterId == fixture.chapter.id,
                state.failedAttempts,
            )
            is ContentSelectorScreenState.Error -> SelectorSnapshot(
                "ERROR",
                0,
                false,
                false,
                state.canonicalTitleId == fixture.title.id && state.canonicalChapterId == fixture.chapter.id,
                0,
            )
        }
    }

    private fun chooseSourceThroughReaderUi(reader: ReaderActivity, sourceName: String) {
        openSourceSelectorThroughReaderUi(reader)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "The selected synthetic source must be offered in the UI",
            device.wait(Until.hasObject(By.text(sourceName)), UI_TIMEOUT_MS),
        )
        device.findObject(By.text(sourceName)).click()
    }

    private fun openSourceSelectorThroughReaderUi(reader: ReaderActivity) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (!device.hasObject(By.text("Choose reading source"))) {
            if (!reader.viewModel.state.value.menuVisible) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
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

    private fun reportDiscoveryDiagnostic(
        typedSelector: SelectorSnapshot,
        fixture: SourceSwitchFixture,
        sourceARoutesBefore: FixtureRouteCounts,
        sourceBRoutesBefore: FixtureRouteCounts,
    ): String {
        // Kept separate from the success marker. The state is read from the
        // production ViewModel only to explain which branch the UI rendered.
        val sourceADelta = fixture.dispatcher.routeCounts(fixture.sourceA.token) - sourceARoutesBefore
        val sourceBDelta = fixture.dispatcher.routeCounts(fixture.sourceB.token) - sourceBRoutesBefore
        val routeSummary = "aSearchDelta=${sourceADelta.search}|aInventoryDelta=${sourceADelta.inventory}" +
            "|aPagesDelta=${sourceADelta.pages}|bSearchDelta=${sourceBDelta.search}" +
            "|bInventoryDelta=${sourceBDelta.inventory}|bPagesDelta=${sourceBDelta.pages}" +
            "|bHeld=${fixture.dispatcher.heldRequestCount(fixture.sourceB.token)}"
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "READER_SELECTOR_DIAGNOSTIC|scenario=SLOW_TO_HEALTHY|state=${typedSelector.state}" +
                        "|options=${typedSelector.options}|aOption=${typedSelector.aOption.toWireDigit()}" +
                        "|bOption=${typedSelector.bOption.toWireDigit()}" +
                        "|chapterMatch=${typedSelector.chapterMatch.toWireDigit()}" +
                        "|failedProviders=${typedSelector.failedProviders}|$routeSummary",
                )
            },
        )
        return routeSummary
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

    private fun awaitReplacementReaderActivity(previous: ReaderActivity): ReaderActivity =
        awaitValue("new resumed ReaderActivity after recreation") {
            var activity: ReaderActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ReaderActivity>()
                    .firstOrNull { it !== previous }
            }
            activity
        }

    private fun confirmPreferredSource(fixture: SourceSwitchFixture, addonId: AddonId) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Successful source preparation should ask before changing the saved preference",
            device.wait(Until.hasObject(By.text("Set preferred Add-on?")), UI_TIMEOUT_MS),
        )
        val oldPreference = if (addonId == fixture.addonB) fixture.addonA else fixture.addonB
        assertEquals(
            "A preferred source changes only after the user confirms the prepared session",
            oldPreference,
            runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId },
        )
        clickText(device, "OK")
        awaitValue("confirmed preferred Add-on to persist") {
            runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId == addonId }
        }
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
        private val originalNavigateToPan: Boolean,
        private val originalSecureScreen: SecurityPreferences.SecureScreenMode,
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
                        app.graph.sourceManager.get(sourceA.id) == null &&
                            app.graph.sourceManager.get(sourceB.id) == null
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
                app.graph.readerPreferences.navigateToPan.set(originalNavigateToPan)
                app.graph.securityPreferences.secureScreen.set(originalSecureScreen)
                server.close()
                driver.close()
            }
        }

        fun canonicalHistoryRowCount(): Long = runBlocking {
            driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM tsuzuki_chapter_history WHERE canonical_chapter_id = ?",
                { cursor ->
                    QueryResult.AsyncValue {
                        check(cursor.next().await()) { "Canonical history count query returned no row" }
                        checkNotNull(cursor.getLong(0))
                    }
                },
                1,
            ) { bindString(0, chapter.id) }.await()
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
                val fixtureTitle = "Synthetic Reader Fixture $runId"
                val server = MockWebServer()
                val dispatcher = ReaderFixtureDispatcher(
                    mapOf(
                        "reader-$runId-a" to FixtureBehavior(pageCount = 10, imageColor = Color.rgb(220, 40, 40)),
                        "reader-$runId-b" to sourceBBehavior.copy(imageColor = Color.rgb(35, 70, 225)),
                    ),
                    mapOf(
                        "reader-$runId-a" to fixtureTitle,
                        "reader-$runId-b" to fixtureTitle,
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
                installedExtensions.value =
                    priorExtensions + (addonA.value to extensionA) + (addonB.value to extensionB)
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
                        displayTitle = fixtureTitle,
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
                    val originalNavigateToPan = app.graph.readerPreferences.navigateToPan.get()
                    val originalSecureScreen = app.graph.securityPreferences.secureScreen.get()
                    app.graph.securityPreferences.secureScreen.set(SecurityPreferences.SecureScreenMode.NEVER)
                    app.graph.readerPreferences.defaultReadingMode.set(ReadingMode.LEFT_TO_RIGHT.flagValue)
                    app.graph.readerPreferences.navigateToPan.set(false)
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
                        originalNavigateToPan = originalNavigateToPan,
                        originalSecureScreen = originalSecureScreen,
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

    private class ReaderFixtureDispatcher(
        behaviors: Map<String, FixtureBehavior>,
        private val searchTitles: Map<String, String>,
    ) : Dispatcher() {
        private val behaviors = java.util.concurrent.ConcurrentHashMap(behaviors)
        private val pageRequestCounts =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        private val imageRequestCounts =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        private val routeRequestCounts =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        private val heldResponses = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        private val heldRequestCounts =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        fun setBehavior(token: String, behavior: FixtureBehavior) {
            behaviors[token] = behavior
        }

        fun holdResponses(token: String) {
            heldResponses[token] = CountDownLatch(1)
        }

        fun releaseResponses(token: String) {
            heldResponses.remove(token)?.countDown()
        }

        fun heldRequestCount(token: String): Int = heldRequestCounts[token]?.get() ?: 0

        fun pageRequestCount(token: String): Int = pageRequestCounts[token]?.get() ?: 0

        fun imageRequestCount(token: String): Int = imageRequestCounts[token]?.get() ?: 0

        fun routeCounts(token: String): FixtureRouteCounts = FixtureRouteCounts(
            search = routeRequestCount(token, "search"),
            inventory = routeRequestCount(token, "inventory"),
            pages = routeRequestCount(token, "pages"),
        )

        private fun routeRequestCount(token: String, route: String): Int =
            routeRequestCounts["$token:$route"]?.get() ?: 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            if (path.startsWith("/reader/")) {
                val token = path.removePrefix("/reader/").substringBefore('/')
                val behavior = behaviors[token] ?: return MockResponse.Builder()
                    .code(404)
                    .body("unknown fixture source")
                    .build()
                val route = when (path) {
                    "/reader/$token/search" -> "search"
                    "/reader/$token/manga" -> "inventory"
                    "/reader/$token/chapter-1" -> "pages"
                    else -> "other"
                }
                routeRequestCounts.computeIfAbsent("$token:$route") {
                    java.util.concurrent.atomic.AtomicInteger()
                }.incrementAndGet()
                val responseGate = heldResponses[token]
                if (responseGate != null) {
                    heldRequestCounts.computeIfAbsent(token) {
                        java.util.concurrent.atomic.AtomicInteger()
                    }.incrementAndGet()
                    try {
                        responseGate.await(30, TimeUnit.SECONDS)
                    } finally {
                        heldRequestCounts[token]?.decrementAndGet()
                    }
                }
                if (behavior.responseDelayMillis > 0) Thread.sleep(behavior.responseDelayMillis)
                if (path == "/reader/$token/search") {
                    val title = searchTitles[token] ?: return errorResponse(404)
                    return textResponse("/reader/$token/manga\t$title")
                }
                if (path == "/reader/$token/manga") {
                    if (behavior.inventoryStatus != 200) return errorResponse(behavior.inventoryStatus)
                    return textResponse("/reader/$token/chapter-1\tChapter 1\t1\tSynthetic group")
                }
                if (path == "/reader/$token/chapter-1") {
                    pageRequestCounts.computeIfAbsent(token) {
                        java.util.concurrent.atomic.AtomicInteger()
                    }.incrementAndGet()
                    if (behavior.pageListStatus != 200) return errorResponse(behavior.pageListStatus)
                    val pages = (0 until behavior.pageCount).joinToString("\n") { "/image/$token/$it.png" }
                    return textResponse(pages)
                }
            }
            if (path.startsWith("/image/")) {
                val token = path.removePrefix("/image/").substringBefore('/')
                imageRequestCounts.computeIfAbsent(token) {
                    java.util.concurrent.atomic.AtomicInteger()
                }.incrementAndGet()
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
            val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
            }
            val bytes = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
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
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            GET("$baseUrl/reader/$token/search")

        @Deprecated("instrumented source fixture")
        override fun searchMangaParse(response: Response): MangasPage = MangasPage(
            mangas = response.body.string()
                .lineSequence()
                .filter(String::isNotBlank)
                .map { line ->
                    val (url, title) = line.split('\t', limit = 2)
                    SManga.create().apply {
                        this.url = url
                        this.title = title
                    }
                }
                .toList(),
            hasNextPage = false,
        )

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
        const val MIN_IMAGE_COLOR_SAMPLES = 80
        const val MIN_IMAGE_COLOR_ROWS = 10
        const val MIN_IMAGE_COLOR_RUN_SAMPLES = 8
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

private data class ReaderViewSnapshot(
    val viewer: String,
    val focused: String,
    val windowSecure: Boolean,
    val windowHasFocus: Boolean,
    val streamPresent: Boolean,
    val pagesLoaded: Boolean,
    val pageCount: Int,
    val pageState: String,
    val position: Int,
    val pager: PagerSnapshot,
)

private data class PagerSnapshot(
    val visible: Boolean,
    val count: Int,
    val currentItem: Int,
    val idle: Boolean,
    val holderPresent: Boolean,
    val holderAttached: Boolean,
    val holderVisible: Boolean,
    val imageViewPresent: Boolean,
    val imageViewVisible: Boolean,
    val imageViewReady: Boolean,
    val errorVisible: Boolean,
)

private data class PagerTapPoint(val x: Int, val y: Int)

private data class SelectorSnapshot(
    val state: String,
    val options: Int,
    val aOption: Boolean,
    val bOption: Boolean,
    val chapterMatch: Boolean,
    val failedProviders: Int,
)

private data class FixtureRouteCounts(
    val search: Int,
    val inventory: Int,
    val pages: Int,
) {
    operator fun minus(previous: FixtureRouteCounts) = FixtureRouteCounts(
        search = (search - previous.search).coerceAtLeast(0),
        inventory = (inventory - previous.inventory).coerceAtLeast(0),
        pages = (pages - previous.pages).coerceAtLeast(0),
    )
}
