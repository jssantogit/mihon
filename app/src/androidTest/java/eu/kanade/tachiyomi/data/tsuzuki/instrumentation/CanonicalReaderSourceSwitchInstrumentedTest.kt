package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import cafe.adriel.voyager.navigator.Navigator
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterInventoryGateway
import eu.kanade.tachiyomi.data.tsuzuki.MihonInventorySnapshotCache
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonAddonProviderFactory
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonChapterProbeProvider
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
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreen
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
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
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.SourceTitleMappingRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentBindingRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentPreferenceRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.functions.Function2

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
            awaitValue("recreated Reader to own a newly attached Viewer") {
                val viewer = recreated.viewModel.state.value.viewer
                viewer?.takeIf {
                    it.getView().parent === recreated.binding.viewerContainer &&
                        it.getView().isAttachedToWindow
                }
            }
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

    @Test(timeout = 240_000L)
    fun legacyIntentAttachesPersistedCanonicalMappingAndRecordsCanonicalProgress() {
        requireOptIn()
        withFixture { fixture ->
            val legacy = fixture.seedLegacyEntry()
            fixture.launchReader(legacy)
            val reader = awaitActivity(ReaderActivity::class.java)
            val initial = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)

            assertTrue(
                "ReaderActivity.newIntent must attach its persisted source mapping to a canonical session",
                reader.viewModel.canChangeCanonicalSource(),
            )
            assertEquals(legacy.manga.id, reader.viewModel.mangaId)
            assertEquals(legacy.chapter.id, reader.viewModel.state.value.currentChapter?.chapter?.id)
            assertEquals(0, initial.first)
            assertEquals(
                "Legacy attach must preserve the existing preferred Add-on",
                fixture.addonA,
                runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId },
            )

            val position = advanceToPage(reader, fixture, 2, "LEGACY_ATTACH")
            awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "LEGACY_ATTACH")
            val progress = awaitValue("canonical progress recorded from the legacy Reader entry") {
                runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
                    ?.takeIf { it.lastPageRead >= position.toLong() }
            }
            // ReaderActivity persists the active chapter's history on pause. Use the real
            // back navigation path before asserting history so this proves the legacy
            // session's canonical identity reached the production lifecycle callback.
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            awaitActivity(MainActivity::class.java)
            val history = awaitValue("canonical history recorded when the legacy Reader pauses") {
                runBlocking { fixture.reading.getHistory(fixture.chapter.id) }
            }
            assertEquals(fixture.chapter.id, progress.canonicalChapterId)
            assertEquals(fixture.chapter.id, history?.canonicalChapterId)
            assertEquals(1L, fixture.canonicalHistoryRowCount())
            assertEquals(
                "Reader attach must not replace the source mapping",
                legacy.mapping,
                fixture.persistedMapping(legacy),
            )
            assertEquals(
                "Reader attach must keep the mapped operational variant stable",
                legacy.variant,
                fixture.persistedVariant(legacy),
            )
            assertEquals(
                "Legacy progress recording must not change the preferred Add-on",
                fixture.addonA,
                runBlocking { fixture.preferences.get(fixture.title.id)?.preferredAddonId },
            )
            report(
                scenario = "LEGACY_ATTACH",
                pageCount = initial.second,
                positionIndex = position,
                details = "legacySessionAttached=true|canonicalIdStable=true|mappingPreserved=true" +
                    "|variantPreserved=true|preferencePreserved=true|canonicalHistory=true",
            )
        }
    }

    @Test(timeout = 240_000L)
    fun legacyReaderKeepsPagesAfterDetailInventoryOwnerCancellation() {
        requireOptIn()
        withFixture(sourceBBehavior = FixtureBehavior(pageCount = 10, inventoryStatus = 503)) { fixture ->
            val legacy = fixture.seedLegacyEntry(persistVariant = false, matchDetailInventoryKey = true)
            fixture.assertInventoryFixtureUrl(legacy)
            fixture.enableSyntheticAddonA()
            val prior = fixture.seedPriorCanonicalState()
            val progressBefore = runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
            val historyBefore = runBlocking { fixture.reading.getHistory(fixture.chapter.id) }
            val preferenceBefore = runBlocking { fixture.preferences.get(fixture.title.id) }
            val token = fixture.sourceA.token
            fixture.dispatcher.holdInventoryResponse(token)
            var appScopeObserverModel: CanonicalTitleScreenModel? = null
            var detailMainActivity: MainActivity? = null
            var inventoryDiagnostics: ChapterInventoryDiagnostics? = null
            var detailReadiness: SourceSwitchFixture.DetailRefreshPrerequisites? = null

            try {
                fixture.launchReader(legacyEntry = legacy) { main ->
                    detailMainActivity = main
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        appScopeObserverModel = fixture.appScopeObserverModel(main)
                    }
                    val observerModel = requireNotNull(appScopeObserverModel)
                    val readiness = fixture.awaitDetailRefreshPrerequisites(observerModel, legacy)
                    detailReadiness = readiness
                    if (!readiness.canRefreshAddonA) {
                        fixture.reportDetailInventorySetup(
                            scenario = "DETAIL_OWNER_CANCEL",
                            routePushed = false,
                            readiness = readiness,
                            legacyEntry = legacy,
                            token = token,
                            owner = main,
                            observerModel = observerModel,
                            diagnostics = inventoryDiagnostics,
                        )
                        throw AssertionError("Detail refresh prerequisites were not ready")
                    }
                    inventoryDiagnostics = fixture.startInventoryDiagnostics(observerModel)
                    // Compose the actual Voyager route so its own ScreenModel calls start().
                    val detailScreen = CanonicalTitleScreen(fixture.title.id)
                    fixture.pushCanonicalTitleScreen(main, detailScreen)
                    try {
                        awaitValue("real canonical title route to render") {
                            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                                .hasObject(By.text("Title details"))
                                .takeIf { it }
                        }
                        awaitValue("real detail route to own the MainActivity navigator") {
                            fixture.isCanonicalTitleScreenActive(main).takeIf { it }
                        }
                        val routeModel = fixture.canonicalTitleScreenModel(main)
                        assertNotSame(
                            "The actual Voyager route must own its own ScreenModel",
                            observerModel,
                            routeModel,
                        )
                        assertSame(
                            "The detail route and observer must share AppScope diagnostics",
                            fixture.chapterInventoryDiagnostics(observerModel),
                            fixture.chapterInventoryDiagnostics(routeModel),
                        )
                        assertSame(
                            "The active route must use the prepared Voyager ScreenModel",
                            detailScreen,
                            fixture.activeCanonicalTitleScreen(main),
                        )
                        assertSame(
                            "The active route must use its prepared ScreenModel",
                            routeModel,
                            fixture.canonicalTitleScreenModel(main),
                        )
                        awaitValue<Boolean>("detail ScreenModel to own the held source A inventory") {
                            val routes = fixture.dispatcher.routeCounts(token)
                            (routes.inventory == 1 && fixture.dispatcher.heldInventoryRequestCount(token) == 1)
                                .takeIf { it }
                        }
                        assertTrue(
                            "The actual source A inventory Call must run off the Android main thread",
                            fixture.sourceA.inventoryCallEvents.get().contains("_APP_REQUEST_ON_MAIN_THREAD_FALSE"),
                        )
                    } catch (error: Throwable) {
                        fixture.reportDetailInventorySetup(
                            scenario = "DETAIL_OWNER_CANCEL",
                            routePushed = true,
                            readiness = requireNotNull(detailReadiness),
                            legacyEntry = legacy,
                            token = token,
                            owner = main,
                            observerModel = requireNotNull(appScopeObserverModel),
                            diagnostics = inventoryDiagnostics,
                        )
                        throw error
                    }
                }

                val reader = awaitActivity(ReaderActivity::class.java)
                val observerModel = requireNotNull(appScopeObserverModel)
                assertSame(
                    "Reader and detail callers must use the same AppScope inventory cache",
                    fixture.readerInventorySnapshotCache(reader),
                    fixture.detailInventorySnapshotCache(observerModel, fixture.addonA),
                )
                awaitValue("legacy Reader attach to wait on the detail-owned inventory") {
                    val state = reader.viewModel.state.value
                    val routes = fixture.dispatcher.routeCounts(token)
                    (
                        state.manga?.id == legacy.manga.id &&
                            state.source?.id == legacy.manga.source &&
                            state.viewerChapters == null &&
                            state.initError == null &&
                            routes.inventory == 1 &&
                            fixture.dispatcher.heldInventoryRequestCount(token) == 1 &&
                            fixture.dispatcher.pageRequestCount(token) == 0
                        )
                        .takeIf { it }
                }
                assertEquals(
                    "The legacy attach must join the pending inventory",
                    1,
                    fixture.dispatcher.routeCounts(token).inventory,
                )
                assertEquals(
                    "Reader pages must wait until the shared attach finishes",
                    0,
                    fixture.dispatcher.pageRequestCount(token),
                )

                fixture.popCanonicalTitleScreen(requireNotNull(detailMainActivity))
                awaitValue("detail route removal to cancel its inventory owner") {
                    (!fixture.isCanonicalTitleScreenActive(requireNotNull(detailMainActivity))).takeIf { it }
                }
                val loaded = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
                assertEquals(
                    "Reader joiner must continue while the cancelled detail response is still held",
                    1,
                    fixture.dispatcher.heldInventoryRequestCount(token),
                )
                fixture.dispatcher.releaseInventoryResponse(token)
                awaitValue("cancelled inventory response to leave the fixture server") {
                    fixture.dispatcher.heldInventoryRequestCount(token).takeIf { it == 0 }
                }
                awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "DETAIL_OWNER_CANCEL")

                assertEquals(0, loaded.first)
                assertEquals(legacy.chapter.id, reader.viewModel.state.value.currentChapter?.chapter?.id)
                assertEquals(
                    "Cancelled detail inventory must not attach a guessed canonical variant",
                    false,
                    reader.viewModel.canChangeCanonicalSource(),
                )
                assertEquals(
                    "No stale A variant may be published after cancellation",
                    null,
                    fixture.persistedVariant(legacy),
                )
                assertEquals(legacy.mapping, fixture.persistedMapping(legacy))
                assertEquals(prior.mapping, fixture.persistedMapping(prior.mapping.sourceId, prior.mapping.sourceUrl))
                assertEquals(
                    prior.variant,
                    fixture.persistedVariant(prior.variant.sourceId, prior.variant.sourceChapterId),
                )
                assertEquals(fixture.title, fixture.persistedCanonicalTitle())
                assertEquals(fixture.chapter, fixture.persistedCanonicalChapter())
                assertEquals(progressBefore, runBlocking { fixture.reading.getProgress(fixture.chapter.id) })
                assertEquals(historyBefore, runBlocking { fixture.reading.getHistory(fixture.chapter.id) })
                assertEquals(preferenceBefore, runBlocking { fixture.preferences.get(fixture.title.id) })
                assertEquals(1L, fixture.canonicalHistoryRowCount())
                report(
                    scenario = "DETAIL_OWNER_CANCEL",
                    pageCount = loaded.second,
                    positionIndex = loaded.first,
                    details = "detailOwnerCancelled=true|readerAttachWaited=true|readerPagesPreserved=true" +
                        "|sharedCacheIdentity=true|mappingPreserved=true|priorVariantPreserved=true" +
                        "|canonicalIdsPreserved=true|progressPreserved=true|historyPreserved=true" +
                        "|preferencePreserved=true|staleTargetVariantAbsent=true|cancelledResponseReleased=true" +
                        "|detailRoute=CanonicalTitleScreen|probe=" +
                        fixture.sanitizedProbeSummary(inventoryDiagnostics),
                )
            } finally {
                inventoryDiagnostics?.stop()
                inventoryDiagnostics?.clear()
                fixture.dispatcher.releaseInventoryResponse(token)
            }
        }
    }

    @Test(timeout = 240_000L)
    fun legacyReaderKeepsPagesAfterDetailInventoryInvalidation() {
        requireOptIn()
        withFixture(sourceBBehavior = FixtureBehavior(pageCount = 10, inventoryStatus = 503)) { fixture ->
            val legacy = fixture.seedLegacyEntry(persistVariant = false, matchDetailInventoryKey = true)
            fixture.assertInventoryFixtureUrl(legacy)
            fixture.enableSyntheticAddonA()
            val prior = fixture.seedPriorCanonicalState()
            val progressBefore = runBlocking { fixture.reading.getProgress(fixture.chapter.id) }
            val historyBefore = runBlocking { fixture.reading.getHistory(fixture.chapter.id) }
            val preferenceBefore = runBlocking { fixture.preferences.get(fixture.title.id) }
            val token = fixture.sourceA.token
            fixture.dispatcher.holdInventoryResponse(token)
            var appScopeObserverModel: CanonicalTitleScreenModel? = null
            var detailMainActivity: MainActivity? = null
            var inventoryDiagnostics: ChapterInventoryDiagnostics? = null
            var detailReadiness: SourceSwitchFixture.DetailRefreshPrerequisites? = null

            try {
                fixture.launchReader(legacyEntry = legacy) { main ->
                    detailMainActivity = main
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        appScopeObserverModel = fixture.appScopeObserverModel(main)
                    }
                    val observerModel = requireNotNull(appScopeObserverModel)
                    val readiness = fixture.awaitDetailRefreshPrerequisites(observerModel, legacy)
                    detailReadiness = readiness
                    if (!readiness.canRefreshAddonA) {
                        fixture.reportDetailInventorySetup(
                            scenario = "DETAIL_INVENTORY_INVALIDATE",
                            routePushed = false,
                            readiness = readiness,
                            legacyEntry = legacy,
                            token = token,
                            owner = main,
                            observerModel = observerModel,
                            diagnostics = inventoryDiagnostics,
                        )
                        throw AssertionError("Detail refresh prerequisites were not ready")
                    }
                    inventoryDiagnostics = fixture.startInventoryDiagnostics(observerModel)
                    // Compose the actual Voyager route so its own ScreenModel calls start().
                    val detailScreen = CanonicalTitleScreen(fixture.title.id)
                    fixture.pushCanonicalTitleScreen(main, detailScreen)
                    try {
                        awaitValue("real canonical title route to render") {
                            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                                .hasObject(By.text("Title details"))
                                .takeIf { it }
                        }
                        awaitValue("real detail route to own the MainActivity navigator") {
                            fixture.isCanonicalTitleScreenActive(main).takeIf { it }
                        }
                        val routeModel = fixture.canonicalTitleScreenModel(main)
                        assertNotSame(
                            "The actual Voyager route must own its own ScreenModel",
                            observerModel,
                            routeModel,
                        )
                        assertSame(
                            "The detail route and observer must share AppScope diagnostics",
                            fixture.chapterInventoryDiagnostics(observerModel),
                            fixture.chapterInventoryDiagnostics(routeModel),
                        )
                        assertSame(
                            "The active route must use the prepared Voyager ScreenModel",
                            detailScreen,
                            fixture.activeCanonicalTitleScreen(main),
                        )
                        assertSame(
                            "The active route must use its prepared ScreenModel",
                            routeModel,
                            fixture.canonicalTitleScreenModel(main),
                        )
                        awaitValue<Boolean>("detail ScreenModel to own the held source A inventory") {
                            val routes = fixture.dispatcher.routeCounts(token)
                            (routes.inventory == 1 && fixture.dispatcher.heldInventoryRequestCount(token) == 1)
                                .takeIf { it }
                        }
                        assertTrue(
                            "The actual source A inventory Call must run off the Android main thread",
                            fixture.sourceA.inventoryCallEvents.get().contains("_APP_REQUEST_ON_MAIN_THREAD_FALSE"),
                        )
                    } catch (error: Throwable) {
                        fixture.reportDetailInventorySetup(
                            scenario = "DETAIL_INVENTORY_INVALIDATE",
                            routePushed = true,
                            readiness = requireNotNull(detailReadiness),
                            legacyEntry = legacy,
                            token = token,
                            owner = main,
                            observerModel = requireNotNull(appScopeObserverModel),
                            diagnostics = inventoryDiagnostics,
                        )
                        throw error
                    }
                }

                val reader = awaitActivity(ReaderActivity::class.java)
                val observerModel = requireNotNull(appScopeObserverModel)
                val readerCache = fixture.readerInventorySnapshotCache(reader)
                assertSame(
                    "Reader and detail callers must use the same AppScope inventory cache",
                    readerCache,
                    fixture.detailInventorySnapshotCache(observerModel, fixture.addonA),
                )
                awaitValue("legacy Reader attach to wait on the detail-owned inventory") {
                    val state = reader.viewModel.state.value
                    val routes = fixture.dispatcher.routeCounts(token)
                    (
                        state.manga?.id == legacy.manga.id &&
                            state.source?.id == legacy.manga.source &&
                            state.viewerChapters == null &&
                            state.initError == null &&
                            routes.inventory == 1 &&
                            fixture.dispatcher.heldInventoryRequestCount(token) == 1 &&
                            fixture.dispatcher.pageRequestCount(token) == 0
                        )
                        .takeIf { it }
                }
                assertEquals(
                    "The legacy attach must join the pending inventory",
                    1,
                    fixture.dispatcher.routeCounts(token).inventory,
                )

                fixture.invalidateSharedInventoryTitle(reader, fixture.title.id)
                val loaded = awaitReaderPages(reader, fixture.sourceA.name, expectedCount = 10)
                awaitImagePixels(reader, fixture, Color.rgb(220, 40, 40), "DETAIL_INVENTORY_INVALIDATE")
                assertEquals(
                    "Reader must keep its valid operational chapter",
                    legacy.chapter.id,
                    reader.viewModel.state.value.currentChapter?.chapter?.id,
                )
                assertEquals(
                    "No stale A variant may be published while its invalidated response is pending",
                    null,
                    fixture.persistedVariant(legacy),
                )
                assertEquals(
                    "The detail owner must still be held while Reader pages load",
                    1,
                    fixture.dispatcher.heldInventoryRequestCount(token),
                )

                fixture.dispatcher.releaseInventoryResponse(token)
                awaitValue("invalidated late inventory response to leave the fixture server") {
                    fixture.dispatcher.heldInventoryRequestCount(token).takeIf { it == 0 }
                }
                awaitValue("detail probe to finish after the invalidated response") {
                    fixture.sanitizedProbeSummary(inventoryDiagnostics)
                        .takeIf { it.contains("CHAPTER_PROBE") }
                }
                fixture.popCanonicalTitleScreen(requireNotNull(detailMainActivity))

                assertEquals(0, loaded.first)
                assertEquals(false, reader.viewModel.canChangeCanonicalSource())
                assertEquals("No late stale A variant may be reconciled", null, fixture.persistedVariant(legacy))
                assertEquals(legacy.mapping, fixture.persistedMapping(legacy))
                assertEquals(prior.mapping, fixture.persistedMapping(prior.mapping.sourceId, prior.mapping.sourceUrl))
                assertEquals(
                    prior.variant,
                    fixture.persistedVariant(prior.variant.sourceId, prior.variant.sourceChapterId),
                )
                assertEquals(fixture.title, fixture.persistedCanonicalTitle())
                assertEquals(fixture.chapter, fixture.persistedCanonicalChapter())
                assertEquals(progressBefore, runBlocking { fixture.reading.getProgress(fixture.chapter.id) })
                assertEquals(historyBefore, runBlocking { fixture.reading.getHistory(fixture.chapter.id) })
                assertEquals(preferenceBefore, runBlocking { fixture.preferences.get(fixture.title.id) })
                assertEquals(1L, fixture.canonicalHistoryRowCount())
                report(
                    scenario = "DETAIL_INVENTORY_INVALIDATE",
                    pageCount = loaded.second,
                    positionIndex = loaded.first,
                    details = "inventoryInvalidated=true|readerAttachWaited=true|readerPagesPreserved=true" +
                        "|sharedCacheIdentity=true|mappingPreserved=true|priorVariantPreserved=true" +
                        "|canonicalIdsPreserved=true|progressPreserved=true|historyPreserved=true" +
                        "|preferencePreserved=true|staleTargetVariantAbsent=true|lateResponseReleased=true" +
                        "|detailRoute=CanonicalTitleScreen|probe=" +
                        fixture.sanitizedProbeSummary(inventoryDiagnostics),
                )
            } finally {
                inventoryDiagnostics?.stop()
                inventoryDiagnostics?.clear()
                fixture.dispatcher.releaseInventoryResponse(token)
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        // The screenshot artifact showed a second, app-owned "Left/Right"
        // navigation guide behind Android's immersive-mode tutorial. Dismiss
        // it via the same performClick() a user's tap invokes, without
        // altering the Reader's image/loading implementation or relaxing
        // screenshot and decoded-image assertions.
        awaitValue("Reader tap-zone guide to clear before visual verification") {
            var dismissed = false
            instrumentation.runOnMainSync {
                val guide = reader.binding.navigationOverlay
                if (guide.visibility == View.VISIBLE) guide.performClick()
                dismissed = guide.visibility != View.VISIBLE
            }
            dismissed.takeIf { it }
        }
        var lastMatchingSampleCount = 0
        var lastMatchingRowCount = 0
        val imageEvidence = try {
            awaitValue("Reader to own the focused window and render its synthetic image") {
                if (!reader.window.decorView.hasWindowFocus()) return@awaitValue null
                val before = readerViewSnapshot(reader)
                if (!isCurrentPageImageVisible(before.pager)) return@awaitValue null
                val screenshot = device.takeScreenshot() ?: return@awaitValue null
                try {
                    val evidence = countImageColorSamples(screenshot, expectedColor)
                    lastMatchingSampleCount = evidence.sampleCount
                    lastMatchingRowCount = evidence.rowsWithLongRun
                    val after = readerViewSnapshot(reader)
                    evidence.takeIf {
                        it.sampleCount >= MIN_IMAGE_COLOR_SAMPLES &&
                            it.rowsWithLongRun >= MIN_IMAGE_COLOR_ROWS &&
                            isCurrentPageImageVisible(after.pager) &&
                            before.position == after.position &&
                            before.pager.currentItem == after.pager.currentItem
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
        // The polling loop proved an attached, decoded current-page view both
        // before and after capturing the expected on-screen image pixels.
        // Do not accept a screenshot of an unrelated or detached old holder.
    }

    private fun isCurrentPageImageVisible(pager: PagerSnapshot): Boolean =
        pager.visible && pager.holderAttached && pager.holderVisible &&
            pager.imageViewPresent && pager.imageViewVisible && pager.imageViewReady && !pager.errorVisible

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
        reportForegroundDiagnostic(scenario)
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

    /** Only fixed categories leave this isolated fixture; never export dumpsys or UI text. */
    private fun reportForegroundDiagnostic(scenario: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val windowDump = runCatching { device.executeShellCommand("dumpsys window") }.getOrDefault("")
        val activityDump = runCatching {
            device.executeShellCommand("dumpsys activity activities")
        }.getOrDefault("")

        fun category(line: String?): String = when {
            line == null -> "UNAVAILABLE"
            line.contains("ReaderActivity") && line.contains(EXPECTED_TARGET_PACKAGE) -> "READER"
            line.contains("MainActivity") && line.contains(EXPECTED_TARGET_PACKAGE) -> "MAIN"
            line.contains("permissioncontroller", ignoreCase = true) -> "PERMISSION_DIALOG"
            line.contains("com.android.internal.app.") -> "ANDROID_ALERT"
            line.contains("com.android.systemui") -> "SYSTEM_UI"
            line.contains("$EXPECTED_TARGET_PACKAGE.test") -> "TEST_RUNNER"
            line.contains("launcher", ignoreCase = true) -> "LAUNCHER"
            line.contains("inputmethod", ignoreCase = true) || line.contains("latinime", ignoreCase = true) ->
                "KEYBOARD"
            line.contains("android/") -> "ANDROID_FRAMEWORK"
            line.contains("null", ignoreCase = true) -> "NONE"
            else -> "OTHER"
        }

        fun findLine(dump: String, marker: String): String? =
            dump.lineSequence().map(String::trim).firstOrNull { it.startsWith(marker) }

        val currentFocus = category(findLine(windowDump, "mCurrentFocus="))
        val focusedApp = category(findLine(windowDump, "mFocusedApp="))
        val topResumed = category(
            findLine(activityDump, "topResumedActivity=")
                ?: findLine(activityDump, "mResumedActivity="),
        )
        val rootType = runCatching {
            val active = instrumentation.uiAutomation.windows.firstOrNull { it.isActive }
            when (active?.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
                null -> "UNAVAILABLE"
                else -> "OTHER"
            }
        }.getOrDefault("UNAVAILABLE")
        val keyguard = runCatching {
            (instrumentation.targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .isKeyguardLocked
        }.getOrDefault(false)
        instrumentation.sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "READER_FOREGROUND_DIAGNOSTIC|scenario=$scenario|currentFocus=$currentFocus" +
                        "|focusedApp=$focusedApp|topResumed=$topResumed" +
                        "|rootType=$rootType|rootPackage=${activeWindowCategory()}" +
                        "|keyguard=${keyguard.toWireBoolean()}",
                )
            },
        )
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

    // Detect a broad high-saturation color family across a contiguous page-sized
    // area, not an exact RGB match (emulators and display pipelines recolor PNGs).
    private fun isNearColor(actual: Int, expected: Int): Boolean {
        val red = Color.red(actual)
        val green = Color.green(actual)
        val blue = Color.blue(actual)
        return when (expected) {
            Color.rgb(220, 40, 40) -> red >= 100 && red >= green * 3 / 2 && red >= blue * 3 / 2
            Color.rgb(35, 70, 225) -> blue >= 100 && blue >= red * 3 / 2 && blue >= green * 4 / 3
            else -> false
        }
    }

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
            val windowLocation = IntArray(2)
            pager.getLocationOnScreen(location)
            pager.getLocationInWindow(windowLocation)
            if (pager.width > 0 && pager.height > 0) {
                // PagerViewer converts event.rawX/Y back into navigator coordinates
                // by adding its window-relative location. Invert that transform
                // so the injected tap actually reaches the NEXT/RIGHT region.
                tapPoint = PagerTapPoint(
                    x = location[0] - windowLocation[0] +
                        (normalizedPoint.x * pager.width).toInt(),
                    y = location[1] - windowLocation[1] +
                        (normalizedPoint.y * pager.height).toInt(),
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
        val activeWindow = activeWindowCategory()
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
            activeWindow = activeWindow,
            streamPresent = page?.stream != null,
            pagesLoaded = chapter?.state is ReaderChapter.State.Loaded,
            pageCount = pages?.size ?: 0,
            pageState = pageStateName(page?.status),
            position = position,
            pager = pagerSnapshot(reader),
        )
    }

    private fun activeWindowCategory(): String {
        val focusedPackage = runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.packageName?.toString()
        }.getOrNull().orEmpty()
        return when {
            focusedPackage == EXPECTED_TARGET_PACKAGE -> "READER_APP"
            focusedPackage == "com.android.systemui" -> "SYSTEM_UI"
            focusedPackage.contains("permissioncontroller", ignoreCase = true) -> "PERMISSION_DIALOG"
            focusedPackage == "$EXPECTED_TARGET_PACKAGE.test" -> "TEST_RUNNER"
            focusedPackage == "com.android.settings" -> "SETTINGS"
            focusedPackage.contains("launcher", ignoreCase = true) -> "LAUNCHER"
            focusedPackage == "android" -> "ANDROID_FRAMEWORK"
            focusedPackage.contains("inputmethod", ignoreCase = true) ||
                focusedPackage.contains("latinime", ignoreCase = true) -> "KEYBOARD"
            focusedPackage == "com.google.android.gms" -> "GOOGLE_PLAY_SERVICES"
            focusedPackage.startsWith("com.android.") -> "ANDROID_SYSTEM"
            focusedPackage.startsWith("com.google.android.") -> "GOOGLE_SYSTEM"
            focusedPackage.isBlank() -> "UNAVAILABLE"
            else -> "OTHER"
        }
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
                        "|activeWindow=${snapshot.activeWindow}" +
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
        private val sourceMangaA: Manga,
        private val sourceMangaB: Manga,
        val addonA: AddonId,
        val addonB: AddonId,
        val title: CanonicalTitle,
        val chapter: CanonicalChapter,
        val preferences: ContentPreferenceRepositoryImpl,
        val reading: CanonicalReadingRepositoryImpl,
        private val originalReaderMode: Int,
        private val originalNavigateToPan: Boolean,
        private val originalSecureScreen: SecurityPreferences.SecureScreenMode,
        private val originalOnboardingCompleted: Boolean,
        private val installedExtensions: MutableStateFlow<Map<String, Extension.Installed>>,
        private val priorExtensions: Map<String, Extension.Installed>,
        private val priorDisabledSources: Set<String>,
    ) {

        fun launchReader(
            legacyEntry: LegacyReaderEntry? = null,
            beforeReaderLaunch: (MainActivity) -> Unit = {},
        ) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val device = UiDevice.getInstance(instrumentation)
            device.wakeUp()
            // A shell-launched exported MainActivity gives the app a real
            // foreground task. Starting the non-exported Reader directly from
            // a background instrumentation context can leave it RESUMED but
            // without window focus, with screenshots capturing another app.
            val foregroundResult = device.executeShellCommand(
                "am start -W -n $EXPECTED_TARGET_PACKAGE/eu.kanade.tachiyomi.ui.main.MainActivity " +
                    "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
            )
            assertTrue("Unable to foreground the isolated Reader test package", !foregroundResult.contains("Error"))
            val main = awaitSourceSwitchFixtureValue("MainActivity to resume before launching Reader") {
                var resumed: MainActivity? = null
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .firstOrNull()
                }
                resumed
            }
            // A RESUMED Activity can still be hidden by an Android-owned window.
            // Establish focus on the actual foreground application before opening
            // Reader from that Activity, keeping both in its real task.
            // API 35's disposable emulator may show a genuine Pixel Launcher
            // ANR above a RESUMED MainActivity. Close only that exact Android
            // system dialog by its visible action; never dismiss Mihon ANRs or
            // weaken the required window-focus and rendered-image assertions.
            var dismissedPixelLauncherAnr = false
            awaitSourceSwitchFixtureValue("MainActivity to own the foreground window") {
                var focused = false
                instrumentation.runOnMainSync { focused = main.window.decorView.hasWindowFocus() }
                if (!focused && !dismissedPixelLauncherAnr &&
                    device.hasObject(By.text("Pixel Launcher isn't responding"))
                ) {
                    val closeLauncher = requireNotNull(device.findObject(By.text("Close app"))) {
                        "Pixel Launcher ANR has no visible Close app action"
                    }
                    closeLauncher.click()
                    dismissedPixelLauncherAnr = true
                }
                focused.takeIf { it }
            }
            beforeReaderLaunch(main)
            // Instrumentation starts the separate singleTask Reader as a
            // foreground Activity and waits for creation. A plain startActivity
            // returned RESUMED with the emulator still focused on another app.
            val readerIntent = legacyEntry?.let {
                ReaderActivity.newIntent(context, it.manga.id, it.chapter.id)
            } ?: ReaderActivity.newCanonicalIntent(context, chapter.id)
            val launched = instrumentation.startActivitySync(
                readerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue("Foreground launch must create ReaderActivity", launched is ReaderActivity)
            // The disposable emulator can show Android's first-use immersive-mode
            // tutorial above Reader ("Viewing full screen" / "Got it"). It is an
            // Android SYSTEM window: ActivityManager reports Reader top-resumed,
            // but screenshots and touch input belong to this tutorial until
            // dismissed. Dismiss only this exact OS prompt in instrumentation;
            // never disable fullscreen behavior in the production Reader.
            if (device.wait(Until.hasObject(By.text("Viewing full screen")), 5_000L)) {
                val confirm = requireNotNull(device.findObject(By.text("Got it"))) {
                    "Android fullscreen tutorial appeared without its confirmation button"
                }
                confirm.click()
                assertTrue(
                    "Android fullscreen tutorial should close after confirmation",
                    device.wait(Until.gone(By.text("Viewing full screen")), 5_000L),
                )
            }
            awaitSourceSwitchFixtureValue("Reader to own focus after Android immersive confirmation") {
                var focused = false
                instrumentation.runOnMainSync {
                    focused = (launched as ReaderActivity).window.decorView.hasWindowFocus()
                }
                focused.takeIf { it }
            }
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
                    legacyEntry?.let {
                        SourceTitleMappingRepositoryImpl(database).remove(it.mapping.id)
                    }
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
                app.graph.basePreferences.shownOnboardingFlow.set(originalOnboardingCompleted)
                app.graph.sourcePreferences.disabledSources.set(priorDisabledSources)
                server.close()
            }
        }

        fun enableSyntheticAddonA() {
            runBlocking { app.graph.addonRepository.setEnabled(addonA, true) }
        }

        fun assertInventoryFixtureUrl(entry: LegacyReaderEntry) {
            val preflight = inventoryGatewayPreflight(entry)
            assertEquals(
                "Fixture source must retain MockWebServer's advertised origin",
                "MATCH",
                preflight.fixtureOrigin,
            )
            assertEquals("Fixture inventory path must match the local dispatcher route", "MATCH", preflight.fixturePath)
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

        fun seedPriorCanonicalState(): PriorCanonicalState {
            val now = System.currentTimeMillis()
            val mapping = SourceTitleMapping(
                id = "binding-${title.id.removePrefix("android-source-switch-")}-b",
                canonicalTitleId = title.id,
                mihonMangaId = sourceMangaB.id,
                sourceId = sourceB.id,
                sourceUrl = sourceMangaB.url,
                language = sourceB.lang,
                matchConfidence = 1.0,
                verifiedByUser = true,
                availability = SourceMappingAvailability.AVAILABLE,
                preferredOverride = false,
                createdAt = now,
                updatedAt = now,
            )
            val chapterUrl = "/reader/${sourceB.token}/chapter-1"
            val operationalChapter = runBlocking {
                ChapterRepositoryImpl(database).addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = sourceMangaB.id,
                            url = chapterUrl,
                            name = "Chapter 1",
                            chapterNumber = 1.0,
                            sourceOrder = 1L,
                            dateUpload = now,
                        ),
                    ),
                ).single()
            }
            val variant = ChapterVariant(
                id = "prior-variant-${title.id}",
                canonicalChapterId = chapter.id,
                sourceMappingId = mapping.id,
                sourceId = sourceB.id,
                mihonMangaId = sourceMangaB.id,
                mihonChapterId = operationalChapter.id,
                sourceChapterId = chapterUrl,
                sourceChapterUrl = chapterUrl,
                language = sourceB.lang,
                scanlationGroup = "Prior synthetic group",
                version = 1L,
                releaseDate = now,
                rawName = "Chapter 1",
                rawNumberHint = 1.0,
                rawSourceOrder = 1L,
                createdAt = now,
                updatedAt = now,
            )
            runBlocking {
                SourceTitleMappingRepositoryImpl(database).upsert(mapping)
                CanonicalChapterRepositoryImpl(database).upsertVariant(variant)
                reading.upsertProgress(
                    CanonicalChapterProgress(
                        canonicalChapterId = chapter.id,
                        lastPageRead = 4L,
                        lastVariantId = variant.id,
                        updatedAt = now,
                    ),
                )
                reading.recordHistory(
                    CanonicalChapterHistoryUpdate(
                        canonicalChapterId = chapter.id,
                        variantId = variant.id,
                        readAt = now,
                        sessionReadDuration = 30_000L,
                    ),
                )
            }
            return PriorCanonicalState(mapping, operationalChapter, variant)
        }

        data class PriorCanonicalState(
            val mapping: SourceTitleMapping,
            val operationalChapter: Chapter,
            val variant: ChapterVariant,
        )

        fun persistedMapping(entry: LegacyReaderEntry): SourceTitleMapping? = runBlocking {
            SourceTitleMappingRepositoryImpl(database).getBySource(entry.mapping.sourceId, entry.mapping.sourceUrl)
        }

        fun persistedMapping(sourceId: Long, sourceUrl: String): SourceTitleMapping? = runBlocking {
            SourceTitleMappingRepositoryImpl(database).getBySource(sourceId, sourceUrl)
        }

        fun persistedVariant(entry: LegacyReaderEntry): ChapterVariant? = runBlocking {
            persistedVariant(entry.variant.sourceId, entry.variant.sourceChapterId)
        }

        fun persistedVariant(sourceId: Long, sourceChapterId: String): ChapterVariant? = runBlocking {
            CanonicalChapterRepositoryImpl(database).getVariantBySourceIdentity(sourceId, sourceChapterId)
        }

        fun persistedCanonicalTitle(): CanonicalTitle? = runBlocking {
            CanonicalTitleRepositoryImpl(database).getById(title.id)
        }

        fun persistedCanonicalChapter(): CanonicalChapter? = runBlocking {
            CanonicalChapterRepositoryImpl(database).getById(chapter.id)
        }

        fun appScopeObserverModel(owner: MainActivity): CanonicalTitleScreenModel =
            ViewModelProvider(owner, app.graph.viewModelFactory).get(CanonicalTitleScreenModel::class.java)

        fun readerInventorySnapshotCache(reader: ReaderActivity): MihonInventorySnapshotCache {
            val refresh = exactPrivateField(
                reader.viewModel,
                "refreshCanonicalChapters",
                RefreshCanonicalChapters::class.java,
            )
            val gateway = exactPrivateField(
                refresh,
                "chapterInventoryGateway",
                MihonChapterInventoryGateway::class.java,
            )
            return exactPrivateField(gateway, "inventoryCache", MihonInventorySnapshotCache::class.java)
        }

        fun detailInventorySnapshotCache(
            model: CanonicalTitleScreenModel,
            addonId: AddonId,
        ): MihonInventorySnapshotCache {
            val refresh = exactPrivateField(
                model,
                "refreshChapterEvidence",
                RefreshChapterEvidence::class.java,
            )
            val addonRegistry = exactPrivateField(refresh, "addonRegistry", AddonRegistry::class.java)
            val provider = addonRegistry.chapterProbeProviders()
                .filterIsInstance<MihonChapterProbeProvider>()
                .singleOrNull { it.addonId == addonId }
                ?: throw AssertionError("Expected exactly one Mihon chapter probe for $addonId")
            val fetchInventory = exactPrivateField(provider, "fetchInventory", Function2::class.java)
            val factoryFields = fetchInventory.javaClass.declaredFields.filter {
                it.type == MihonAddonProviderFactory::class.java
            }
            assertEquals(
                "The real detail inventory lambda must capture exactly one provider factory",
                1,
                factoryFields.size,
            )
            val factory = exactPrivateField(
                fetchInventory,
                factoryFields.single().name,
                MihonAddonProviderFactory::class.java,
            )
            val gateway = exactPrivateField(
                factory,
                "chapterInventoryGateway",
                MihonChapterInventoryGateway::class.java,
            )
            return exactPrivateField(gateway, "inventoryCache", MihonInventorySnapshotCache::class.java)
        }

        data class DetailRefreshPrerequisites(
            val integrationReady: Boolean,
            val addonRegistryReady: Boolean,
            val providerCount: Int?,
            val bindingGate: String,
            val sourceEligible: Boolean,
            val bindingSelection: String,
        ) {
            val canRefreshAddonA: Boolean
                get() = integrationReady && addonRegistryReady && providerCount == 1 &&
                    bindingGate == "ELIGIBLE" && sourceEligible && bindingSelection == "SELECTED"
        }

        fun awaitDetailRefreshPrerequisites(
            model: CanonicalTitleScreenModel,
            legacyEntry: LegacyReaderEntry,
        ): DetailRefreshPrerequisites = runBlocking {
            val refresh = exactPrivateField(
                model,
                "refreshChapterEvidence",
                RefreshChapterEvidence::class.java,
            )
            val integrationRegistry = exactPrivateField(
                refresh,
                "registry",
                IntegrationRegistry::class.java,
            )
            val addonRegistry = exactPrivateField(refresh, "addonRegistry", AddonRegistry::class.java)
            val integrationReady = try {
                withTimeoutOrNull(REGISTRY_READY_TIMEOUT_MS) {
                    integrationRegistry.awaitReady()
                    true
                } ?: false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
            val addonRegistryReady = try {
                withTimeoutOrNull(REGISTRY_READY_TIMEOUT_MS) {
                    addonRegistry.awaitReady()
                    true
                } ?: false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
            val providerCount = runCatching {
                addonRegistry.chapterProbeProviders()
                    .filterIsInstance<MihonChapterProbeProvider>()
                    .count { it.addonId == addonA }
            }.getOrNull()
            val provider = addonRegistry.chapterProbeProviders()
                .filterIsInstance<MihonChapterProbeProvider>()
                .singleOrNull { it.addonId == addonA }
            DetailRefreshPrerequisites(
                integrationReady = integrationReady,
                addonRegistryReady = addonRegistryReady,
                providerCount = providerCount,
                bindingGate = detailBindingGate(legacyEntry),
                sourceEligible = provider?.let { detailProviderSourceEligible(it, legacyEntry) } == true,
                bindingSelection = detailBindingSelection(refresh, legacyEntry),
            )
        }

        fun reportDetailInventorySetup(
            scenario: String,
            routePushed: Boolean,
            readiness: DetailRefreshPrerequisites,
            legacyEntry: LegacyReaderEntry,
            token: String,
            owner: MainActivity,
            observerModel: CanonicalTitleScreenModel,
            diagnostics: ChapterInventoryDiagnostics?,
        ) {
            val navigator = exactPrivateField(owner, "navigator", Navigator::class.java)
            val route = navigator.lastItem as? CanonicalTitleScreen
            val routeModel = route?.let { runCatching { canonicalTitleScreenModel(owner) }.getOrNull() }
            val state = routeModel?.state?.value
            val loaded = state as? CanonicalTitleScreenState.Loaded
            val stateName = when (state) {
                is CanonicalTitleScreenState.Loaded -> "LOADED"
                is CanonicalTitleScreenState.Error -> "ERROR"
                CanonicalTitleScreenState.Loading, null -> "LOADING"
            }
            val stateError = when (state) {
                is CanonicalTitleScreenState.Error -> safeErrorCategory(state.error)
                else -> "NONE"
            }
            val refreshError = safeErrorCategory(loaded?.refreshError)
            val operation = routeModel?.let { optionalPrivateField(it, "operation") as? kotlinx.coroutines.Job }
            val startJob = when {
                operation == null -> "NOT_STARTED"
                operation.isCancelled -> "CANCELLED"
                operation.isActive -> "ACTIVE"
                else -> "COMPLETED"
            }
            val localLoad = when {
                loaded?.title?.id == title.id -> "PASSED"
                state is CanonicalTitleScreenState.Error -> "FAILED"
                else -> "INCOMPLETE"
            }
            val probeSummary = sanitizedProbeSummary(
                diagnostics?.report().orEmpty(),
            )
            val gatewayPreflight = inventoryGatewayPreflight(legacyEntry)
            val actualModel = routeModel != null && routeModel !== observerModel
            InstrumentationRegistry.getInstrumentation().sendStatus(
                1,
                Bundle().apply {
                    putString(
                        "stream",
                        "ANDROID_SOURCE_SWITCH_DETAIL_SETUP|scenario=$scenario" +
                            "|state=$stateName|localLoad=$localLoad" +
                            "|refreshing=${loaded?.isRefreshing?.let { if (it) "TRUE" else "FALSE" } ?: "UNKNOWN"}" +
                            "|startJob=$startJob|stateError=$stateError|refreshError=$refreshError" +
                            "|integrationReady=${if (readiness.integrationReady) "TRUE" else "FALSE"}" +
                            "|addonReady=${if (readiness.addonRegistryReady) "TRUE" else "FALSE"}" +
                            "|providerCount=${readiness.providerCount?.toString() ?: "UNKNOWN"}" +
                            "|bindingGate=${readiness.bindingGate}" +
                            "|sourceEligible=${if (readiness.sourceEligible) "TRUE" else "FALSE"}" +
                            "|bindingSelection=${readiness.bindingSelection}" +
                            "|bindingPayload=${gatewayPreflight.bindingPayload}" +
                            "|manga=${gatewayPreflight.manga}" +
                            "|source=${gatewayPreflight.source}" +
                            "|fixtureOrigin=${gatewayPreflight.fixtureOrigin}" +
                            "|fixturePath=${gatewayPreflight.fixturePath}" +
                            "|aHttp=${sourceA.inventoryCallOutcome.get()}" +
                            "|bHttp=${sourceB.inventoryCallOutcome.get()}" +
                            "|aCallEvents=${sourceA.inventoryCallEvents.get()}" +
                            "|bCallEvents=${sourceB.inventoryCallEvents.get()}" +
                            "|serverRequests=${server.requestCount}" +
                            "|unknownSourceRequests=${dispatcher.unknownSourceRequestCount()}" +
                            "|aOther=${dispatcher.routeCounts(sourceA.token).other}" +
                            "|bOther=${dispatcher.routeCounts(sourceB.token).other}" +
                            "|aChapterRequests=${sourceA.chapterListRequestCount.get()}" +
                            "|bChapterRequests=${sourceB.chapterListRequestCount.get()}" +
                            "|aInventory=${dispatcher.routeCounts(token).inventory}" +
                            "|aHeld=${dispatcher.heldInventoryRequestCount(token)}" +
                            "|routeRendered=${if (routePushed) "TRUE" else "FALSE"}" +
                            "|routeTitleMatches=${if (route?.canonicalTitleId == title.id) "TRUE" else "FALSE"}" +
                            "|routeModel=${if (actualModel) "SCREEN" else "UNKNOWN"}" +
                            "|probe=$probeSummary",
                    )
                },
            )
        }

        private data class InventoryGatewayPreflight(
            val bindingPayload: String,
            val manga: String,
            val source: String,
            val fixtureOrigin: String,
            val fixturePath: String,
        )

        private fun inventoryGatewayPreflight(legacyEntry: LegacyReaderEntry): InventoryGatewayPreflight {
            val binding = try {
                runBlocking {
                    ContentBindingRepositoryImpl(database)
                        .getByTitle(title.id)
                        .singleOrNull { it.id == legacyEntry.mapping.id }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            val payload = binding?.let {
                runCatching { MihonContentBindingPayloadCodec.decode(it.runtimePayload) }.getOrNull()
            }
            val bindingPayload = when {
                binding == null -> "MISSING"
                payload == null -> "INVALID"
                payload.mihonMangaId != legacyEntry.mapping.mihonMangaId ||
                    payload.sourceId != legacyEntry.mapping.sourceId ||
                    payload.sourceUrl != legacyEntry.mapping.sourceUrl -> "MISMATCH"
                else -> "MATCH"
            }
            val serverUrl = URI(server.url("/").toString())
            val sourceBaseUrl = URI(sourceA.baseUrl)
            val expectedInventoryUrl = URI(server.url(legacyEntry.manga.url).toString())
            val actualInventoryUrl = URI(sourceA.baseUrl + legacyEntry.manga.url)
            val fixtureOrigin = if (
                serverUrl.scheme == sourceBaseUrl.scheme &&
                serverUrl.host == sourceBaseUrl.host &&
                serverUrl.port == sourceBaseUrl.port
            ) {
                "MATCH"
            } else {
                "MISMATCH"
            }
            val fixturePath = if (expectedInventoryUrl.rawPath == actualInventoryUrl.rawPath) {
                "MATCH"
            } else {
                "MISMATCH"
            }
            val manga = when {
                payload == null -> "UNAVAILABLE"
                else -> try {
                    val row = runBlocking { MangaRepositoryImpl(database).getMangaById(payload.mihonMangaId) }
                    if (
                        row.id == payload.mihonMangaId &&
                        row.source == payload.sourceId &&
                        row.url == payload.sourceUrl
                    ) {
                        "MATCH"
                    } else {
                        "MISMATCH"
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: NoSuchElementException) {
                    "MISSING"
                } catch (_: Throwable) {
                    "ERROR"
                }
            }
            val source = try {
                val source = runBlocking { app.graph.sourceManager.get(legacyEntry.mapping.sourceId) }
                when {
                    source == null -> "MISSING"
                    source === sourceA -> "SOURCE_A"
                    source is StubSource -> "STUB"
                    else -> "OTHER"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                "ERROR"
            }
            return InventoryGatewayPreflight(bindingPayload, manga, source, fixtureOrigin, fixturePath)
        }

        private fun safeErrorCategory(error: Throwable?): String = when (error) {
            null -> "NONE"
            is NoSuchElementException -> "NO_SUCH_ELEMENT"
            is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
            is IllegalStateException -> "ILLEGAL_STATE"
            is java.io.IOException -> "IO"
            else -> "OTHER"
        }

        private fun optionalPrivateField(instance: Any, name: String): Any? {
            val field = try {
                instance.javaClass.getDeclaredField(name)
            } catch (error: NoSuchFieldException) {
                throw AssertionError("Expected exact field ${instance.javaClass.name}.$name", error)
            }
            field.isAccessible = true
            return field.get(instance)
        }

        fun startInventoryDiagnostics(model: CanonicalTitleScreenModel): ChapterInventoryDiagnostics {
            val diagnostics = exactPrivateField(
                model,
                "diagnostics",
                ChapterInventoryDiagnostics::class.java,
            )
            diagnostics.start(title.id)
            return diagnostics
        }

        fun chapterInventoryDiagnostics(model: CanonicalTitleScreenModel): ChapterInventoryDiagnostics =
            exactPrivateField(model, "diagnostics", ChapterInventoryDiagnostics::class.java)

        fun canonicalTitleScreenModel(owner: MainActivity): CanonicalTitleScreenModel =
            canonicalTitleScreenModel(activeCanonicalTitleScreen(owner))

        fun canonicalTitleScreenModel(screen: CanonicalTitleScreen): CanonicalTitleScreenModel {
            assertEquals("The active detail route must match the fixture title", title.id, screen.canonicalTitleId)

            // Voyager owns a ViewModelStore per screen. Resolve that exact owner so the test
            // observes the ScreenModel used by CanonicalTitleScreen.Content, not an Activity model.
            val ownerClass = Class.forName("cafe.adriel.voyager.androidx.AndroidScreenLifecycleOwner")
            val companion = ownerClass.getField("Companion").get(null)
            val screenInterface = Class.forName("cafe.adriel.voyager.core.screen.Screen")
            val screenOwnerCandidate = try {
                companion.javaClass.getMethod("get", screenInterface).invoke(companion, screen)
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw error.targetException
            }
            val screenOwner = screenOwnerCandidate as? ViewModelStoreOwner
                ?: throw AssertionError("Voyager did not provide a ViewModelStoreOwner for the active screen")
            return ViewModelProvider(screenOwner, app.graph.viewModelFactory)
                .get(CanonicalTitleScreenModel::class.java)
        }

        fun activeCanonicalTitleScreen(owner: MainActivity): CanonicalTitleScreen =
            exactPrivateField(owner, "navigator", Navigator::class.java).lastItem as? CanonicalTitleScreen
                ?: throw AssertionError("Expected the active Voyager route to be CanonicalTitleScreen")

        fun sanitizedProbeSummary(diagnostics: ChapterInventoryDiagnostics?): String =
            sanitizedProbeSummary(diagnostics?.report().orEmpty())

        private fun sanitizedProbeSummary(report: String): String = report.lineSequence()
            .filter { line ->
                line.startsWith("UI|") ||
                    line.startsWith("CHAPTER_PROBE|") ||
                    line.startsWith("CHAPTER_INVENTORY|")
            }
            .map { line ->
                val stage = line.substringBefore('|')
                val outcome = line.substringAfter("outcome=", "UNKNOWN").substringBefore('|')
                val reason = line.substringAfter("reasons=", "")
                    .substringBefore('|')
                    .substringBefore(',')
                    .substringBefore(':')
                    .ifBlank { "NONE" }
                listOf(stage, outcome, reason.takeIf(String::isNotBlank)).filterNotNull().joinToString(":")
            }
            .take(8)
            .joinToString(",")
            .ifBlank { "NONE" }

        fun pushCanonicalTitleScreen(owner: MainActivity, screen: CanonicalTitleScreen) {
            val navigator = exactPrivateField(owner, "navigator", Navigator::class.java)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                navigator.push(screen)
            }
        }

        fun popCanonicalTitleScreen(owner: MainActivity) {
            val navigator = exactPrivateField(owner, "navigator", Navigator::class.java)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                navigator.pop()
            }
        }

        fun isCanonicalTitleScreenActive(owner: MainActivity): Boolean {
            val navigator = exactPrivateField(owner, "navigator", Navigator::class.java)
            var active = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                active = navigator.lastItem is CanonicalTitleScreen
            }
            return active
        }

        private suspend fun detailBindingGate(legacyEntry: LegacyReaderEntry): String {
            return try {
                val binding = ContentBindingRepositoryImpl(database)
                    .getByTitle(title.id)
                    .singleOrNull { it.id == legacyEntry.mapping.id }
                    ?: return "MISSING"
                if (binding.addonId != addonA) return "WRONG_ADDON"
                if (binding.availability != ContentBindingAvailability.AVAILABLE) return "UNAVAILABLE"
                val addon = app.graph.addonRepository.snapshot().singleOrNull { it.id == addonA }
                    ?: return "ADDON_MISSING"
                if (!addon.enabled) return "ADDON_DISABLED"
                val sourceId = binding.providerTitleKey.substringBefore(':').toLongOrNull()
                    ?: return "INVALID_SOURCE_KEY"
                if (sourceId !in addon.mihonSourceIds) return "SOURCE_NOT_ENABLED"
                "ELIGIBLE"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                "UNKNOWN"
            }
        }

        private suspend fun detailProviderSourceEligible(
            provider: MihonChapterProbeProvider,
            legacyEntry: LegacyReaderEntry,
        ): Boolean {
            val fetchInventory = exactPrivateField(provider, "fetchInventory", Function2::class.java)
            val factoryFields = fetchInventory.javaClass.declaredFields.filter {
                it.type == MihonAddonProviderFactory::class.java
            }
            if (factoryFields.size != 1) return false
            val factory = exactPrivateField(
                fetchInventory,
                factoryFields.single().name,
                MihonAddonProviderFactory::class.java,
            )
            val eligibilityRepository = exactPrivateField(
                factory,
                "sourceEligibilityRepository",
                AddonSourceEligibilityRepository::class.java,
            )
            return eligibilityRepository.getByAddonId(addonA)
                .singleOrNull { it.sourceId == legacyEntry.mapping.sourceId }
                ?.enabled == true
        }

        private suspend fun detailBindingSelection(
            refresh: RefreshChapterEvidence,
            legacyEntry: LegacyReaderEntry,
        ): String {
            return try {
                val resolver = exactPrivateField(
                    refresh,
                    "resolveContentBinding",
                    ResolveContentBinding::class.java,
                )
                val selected = resolver.existingBindingsForRefresh(title.id, addonA).getOrThrow()
                    .singleOrNull { it.id == legacyEntry.mapping.id }
                if (selected == null) "MISSING" else "SELECTED"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                "ERROR"
            }
        }

        fun invalidateSharedInventoryTitle(reader: ReaderActivity, titleId: String) {
            runBlocking { readerInventorySnapshotCache(reader).invalidateTitle(titleId) }
        }

        private fun <T : Any> exactPrivateField(instance: Any, name: String, expectedType: Class<T>): T {
            val field = try {
                instance.javaClass.getDeclaredField(name)
            } catch (error: NoSuchFieldException) {
                throw AssertionError("Expected exact field ${instance.javaClass.name}.$name", error)
            }
            assertTrue(
                "${instance.javaClass.name}.$name has type ${field.type.name}, expected ${expectedType.name}",
                expectedType.isAssignableFrom(field.type),
            )
            field.isAccessible = true
            val value = field.get(instance)
            assertTrue(
                "${instance.javaClass.name}.$name did not contain ${expectedType.name}",
                expectedType.isInstance(value),
            )
            return expectedType.cast(value)
        }

        private var legacyEntry: LegacyReaderEntry? = null

        fun seedLegacyEntry(
            persistVariant: Boolean = true,
            matchDetailInventoryKey: Boolean = false,
        ): LegacyReaderEntry {
            check(legacyEntry == null) { "Legacy Reader fixture entry was already seeded" }
            val now = System.currentTimeMillis()
            val bindingSuffix = title.id.removePrefix("android-source-switch-")
            val mapping = SourceTitleMapping(
                id = if (matchDetailInventoryKey) "binding-$bindingSuffix-a" else "legacy-mapping-${title.id}",
                canonicalTitleId = title.id,
                mihonMangaId = sourceMangaA.id,
                sourceId = sourceA.id,
                sourceUrl = sourceMangaA.url,
                language = sourceA.lang,
                matchConfidence = 1.0,
                verifiedByUser = true,
                availability = SourceMappingAvailability.AVAILABLE,
                preferredOverride = true,
                createdAt = now,
                updatedAt = now,
            )
            val chapterUrl = "/reader/${sourceA.token}/chapter-1"
            val operationalChapter = runBlocking {
                ChapterRepositoryImpl(database).addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = sourceMangaA.id,
                            url = chapterUrl,
                            name = "Chapter 1",
                            chapterNumber = 1.0,
                            sourceOrder = 1L,
                            dateUpload = now,
                        ),
                    ),
                ).single()
            }
            val variant = ChapterVariant(
                id = "legacy-variant-${title.id}",
                canonicalChapterId = chapter.id,
                sourceMappingId = mapping.id,
                sourceId = sourceA.id,
                mihonMangaId = sourceMangaA.id,
                mihonChapterId = operationalChapter.id,
                sourceChapterId = chapterUrl,
                sourceChapterUrl = chapterUrl,
                language = sourceA.lang,
                scanlationGroup = "Synthetic group",
                version = 1L,
                releaseDate = now,
                rawName = "Chapter 1",
                rawNumberHint = 1.0,
                rawSourceOrder = 1L,
                createdAt = now,
                updatedAt = now,
            )
            runBlocking {
                SourceTitleMappingRepositoryImpl(database).upsert(mapping)
                if (persistVariant) CanonicalChapterRepositoryImpl(database).upsertVariant(variant)
            }
            return LegacyReaderEntry(sourceMangaA, operationalChapter, mapping, variant).also {
                legacyEntry = it
            }
        }

        data class LegacyReaderEntry(
            val manga: Manga,
            val chapter: Chapter,
            val mapping: SourceTitleMapping,
            val variant: ChapterVariant,
        )

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
                val priorDisabledSources = app.graph.sourcePreferences.disabledSources.get()

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
                val baseUrl = server.url("/").toString().trimEnd('/')
                val sourceAInventoryCall = AtomicReference("NOT_STARTED")
                val sourceBInventoryCall = AtomicReference("NOT_STARTED")
                val sourceAInventoryCallEvents = AtomicReference("NONE")
                val sourceBInventoryCallEvents = AtomicReference("NONE")
                val sourceA = ReaderFixtureHttpSource(
                    displayName = "Synthetic Reader A $runId",
                    token = "reader-$runId-a",
                    baseUrl = baseUrl,
                    client = fixtureHttpClient(
                        token = "reader-$runId-a",
                        inventoryCallOutcome = sourceAInventoryCall,
                        inventoryCallEvents = sourceAInventoryCallEvents,
                        expectedOrigin = URI(baseUrl),
                    ),
                    inventoryCallOutcome = sourceAInventoryCall,
                    inventoryCallEvents = sourceAInventoryCallEvents,
                )
                val sourceB = ReaderFixtureHttpSource(
                    displayName = "Synthetic Reader B $runId",
                    token = "reader-$runId-b",
                    baseUrl = baseUrl,
                    client = fixtureHttpClient(
                        token = "reader-$runId-b",
                        inventoryCallOutcome = sourceBInventoryCall,
                        inventoryCallEvents = sourceBInventoryCallEvents,
                        expectedOrigin = URI(baseUrl),
                    ),
                    inventoryCallOutcome = sourceBInventoryCall,
                    inventoryCallEvents = sourceBInventoryCallEvents,
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

                // Reader and fixture must share AppScope's database connection.
                // An independently opened driver can race Reader writes and fail SQLITE_LOCKED.
                val driver = app.graph.sqlDriver
                val database = app.graph.database
                try {
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
                    val originalOnboardingCompleted = app.graph.basePreferences.shownOnboardingFlow.get()
                    // The successful legacy navigation fixture completes onboarding;
                    // source-switch tests require the same foreground precondition.
                    app.graph.basePreferences.shownOnboardingFlow.set(true)
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
                        sourceMangaA = sourceMangas.getValue(sourceA.id),
                        sourceMangaB = sourceMangas.getValue(sourceB.id),
                        addonA = addonA,
                        addonB = addonB,
                        title = title,
                        chapter = chapter,
                        preferences = preferences,
                        reading = CanonicalReadingRepositoryImpl(database),
                        originalReaderMode = originalReaderMode,
                        originalNavigateToPan = originalNavigateToPan,
                        originalSecureScreen = originalSecureScreen,
                        originalOnboardingCompleted = originalOnboardingCompleted,
                        installedExtensions = installedExtensions,
                        priorExtensions = priorExtensions,
                        priorDisabledSources = priorDisabledSources,
                    )
                } catch (error: Throwable) {
                    installedExtensions.value = priorExtensions
                    app.graph.sourcePreferences.disabledSources.set(priorDisabledSources)
                    server.close()
                    // AppScope owns the shared driver; do not close it from this fixture.
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
        private val unknownSourceRequests = AtomicInteger()
        private val heldResponses = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        private val heldInventoryResponses = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        private val heldRequestCounts =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        private val heldInventoryRequestCounts =
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

        fun holdInventoryResponse(token: String) {
            heldInventoryResponses[token] = CountDownLatch(1)
        }

        fun releaseInventoryResponse(token: String) {
            heldInventoryResponses.remove(token)?.countDown()
        }

        fun heldRequestCount(token: String): Int = heldRequestCounts[token]?.get() ?: 0

        fun heldInventoryRequestCount(token: String): Int = heldInventoryRequestCounts[token]?.get() ?: 0

        fun pageRequestCount(token: String): Int = pageRequestCounts[token]?.get() ?: 0

        fun imageRequestCount(token: String): Int = imageRequestCounts[token]?.get() ?: 0

        fun unknownSourceRequestCount(): Int = unknownSourceRequests.get()

        fun routeCounts(token: String): FixtureRouteCounts = FixtureRouteCounts(
            search = routeRequestCount(token, "search"),
            inventory = routeRequestCount(token, "inventory"),
            pages = routeRequestCount(token, "pages"),
            other = routeRequestCount(token, "other"),
        )

        private fun routeRequestCount(token: String, route: String): Int =
            routeRequestCounts["$token:$route"]?.get() ?: 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            if (path.startsWith("/reader/")) {
                val token = path.removePrefix("/reader/").substringBefore('/')
                val behavior = behaviors[token] ?: run {
                    unknownSourceRequests.incrementAndGet()
                    return MockResponse.Builder()
                        .code(404)
                        .body("unknown fixture source")
                        .build()
                }
                val route = when (path) {
                    "/reader/$token/search" -> "search"
                    "/reader/$token/manga" -> "inventory"
                    "/reader/$token/chapter-1" -> "pages"
                    else -> "other"
                }
                routeRequestCounts.computeIfAbsent("$token:$route") {
                    java.util.concurrent.atomic.AtomicInteger()
                }.incrementAndGet()
                val inventoryGate = heldInventoryResponses[token].takeIf { route == "inventory" }
                val responseGate = inventoryGate ?: heldResponses[token]
                if (responseGate != null) {
                    heldRequestCounts.computeIfAbsent(token) {
                        java.util.concurrent.atomic.AtomicInteger()
                    }.incrementAndGet()
                    if (inventoryGate != null) {
                        heldInventoryRequestCounts.computeIfAbsent(token) {
                            java.util.concurrent.atomic.AtomicInteger()
                        }.incrementAndGet()
                    }
                    try {
                        responseGate.await(30, TimeUnit.SECONDS)
                    } finally {
                        heldRequestCounts[token]?.decrementAndGet()
                        if (inventoryGate != null) {
                            heldInventoryRequestCounts[token]?.decrementAndGet()
                        }
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
        val inventoryCallOutcome: AtomicReference<String>,
        val inventoryCallEvents: AtomicReference<String>,
    ) : HttpSource() {
        val chapterListRequestCount = AtomicInteger()
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
        override fun chapterListRequest(manga: SManga): Request {
            chapterListRequestCount.incrementAndGet()
            return GET("$baseUrl${manga.url}")
        }

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
        const val REGISTRY_READY_TIMEOUT_MS = 15_000L
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
    val activeWindow: String,
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
    val other: Int,
) {
    operator fun minus(previous: FixtureRouteCounts) = FixtureRouteCounts(
        search = (search - previous.search).coerceAtLeast(0),
        inventory = (inventory - previous.inventory).coerceAtLeast(0),
        pages = (pages - previous.pages).coerceAtLeast(0),
        other = (other - previous.other).coerceAtLeast(0),
    )
}

private fun fixtureHttpClient(
    token: String,
    inventoryCallOutcome: AtomicReference<String>,
    inventoryCallEvents: AtomicReference<String>,
    expectedOrigin: URI,
): OkHttpClient {
    fun callIdentifier(call: Call): String = java.lang.Integer.toHexString(System.identityHashCode(call))

    fun requestKind(url: okhttp3.HttpUrl): String {
        val originMatches = url.scheme == expectedOrigin.scheme &&
            url.host == expectedOrigin.host &&
            url.port == expectedOrigin.port
        if (!originMatches) return "ORIGIN_MISMATCH"
        return when {
            url.encodedPath == "/reader/$token/manga" -> "INVENTORY"
            url.encodedPath.startsWith("/reader/$token/") -> "SOURCE_OTHER"
            else -> "OTHER"
        }
    }

    fun recordEvent(callId: String, request: Request, event: String) {
        val value = "C$callId:${requestKind(request.url)}_$event"
        inventoryCallEvents.updateAndGet { current ->
            val prior = current.split(',').filter { it != "NONE" }
            (prior + value).takeLast(8).joinToString(",").ifBlank { "NONE" }
        }
    }

    fun failureCategory(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "cleartext" in message -> "CLEARTEXT_BLOCKED"
            error is UnknownHostException -> "DNS_FAILURE"
            error is ConnectException && "refused" in message -> "CONNECTION_REFUSED"
            error is ConnectException -> "CONNECT_FAILURE"
            error is SocketTimeoutException -> "SOCKET_TIMEOUT"
            "canceled" in message -> "CANCELLED"
            error is IOException -> "OTHER_IO_FAILURE"
            else -> "OTHER_EXCEPTION"
        }
    }

    fun exceptionTypeCategory(error: Throwable?): String = when (error) {
        null -> "NONE"
        is android.os.NetworkOnMainThreadException -> "NETWORK_ON_MAIN_THREAD"
        is IOException -> "IO_EXCEPTION"
        is CancellationException -> "CANCELLATION"
        is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
        is IllegalStateException -> "ILLEGAL_STATE"
        is SecurityException -> "SECURITY_EXCEPTION"
        else -> "OTHER_EXCEPTION"
    }

    fun recordAppFailure(callId: String, request: Request, error: Throwable) {
        recordEvent(callId, request, "APP_FAIL_${failureCategory(error)}")
        recordEvent(callId, request, "APP_FAILURE_TYPE_${exceptionTypeCategory(error)}")
        recordEvent(callId, request, "APP_FAILURE_CAUSE_${exceptionTypeCategory(error.cause)}")
        val onMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        recordEvent(callId, request, "APP_FAILURE_ON_MAIN_THREAD_${onMainThread.toString().uppercase()}")
    }

    return OkHttpClient.Builder()
        .addInterceptor { chain ->
            val call = chain.call()
            val callId = callIdentifier(call)
            val request = chain.request()
            val cacheControl = request.cacheControl
            val requestOnMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            recordEvent(
                callId,
                request,
                "APP_REQUEST_ONLY_IF_CACHED_${cacheControl.onlyIfCached.toString().uppercase()}",
            )
            recordEvent(
                callId,
                request,
                "APP_REQUEST_ON_MAIN_THREAD_${requestOnMainThread.toString().uppercase()}",
            )
            val hasCacheControlHeader = request.header("Cache-Control") != null
            recordEvent(
                callId,
                request,
                "APP_REQUEST_CACHE_CONTROL_HEADER_PRESENT_${hasCacheControlHeader.toString().uppercase()}",
            )
            val maxAgeCategory = when {
                cacheControl.maxAgeSeconds < 0 -> "NONE"
                cacheControl.maxAgeSeconds == 0 -> "ZERO"
                else -> "POSITIVE"
            }
            recordEvent(callId, request, "APP_REQUEST_MAX_AGE_$maxAgeCategory")
            try {
                val response = chain.proceed(request)
                val responseSource = when {
                    response.cacheResponse != null && response.networkResponse != null -> "CACHE_AND_NETWORK"
                    response.cacheResponse != null -> "CACHE_ONLY"
                    response.networkResponse != null -> "NETWORK_ONLY"
                    else -> "NO_CACHE_OR_NETWORK_METADATA"
                }
                recordEvent(callId, response.request, "APP_RESPONSE_HTTP_${response.code}_$responseSource")
                response
            } catch (error: IOException) {
                recordAppFailure(callId, request, error)
                throw error
            } catch (error: Throwable) {
                recordAppFailure(callId, request, error)
                throw error
            }
        }
        .eventListenerFactory { call ->
            val callId = callIdentifier(call)
            object : EventListener() {
                private fun isInventoryCall(call: Call): Boolean =
                    call.request().url.encodedPath == "/reader/$token/manga"

                private fun recordEvent(call: Call, event: String, request: Request = call.request()) {
                    recordEvent(callId, request, event)
                }

                override fun callStart(call: Call) {
                    recordEvent(call, "START")
                    if (isInventoryCall(call)) inventoryCallOutcome.set("STARTED")
                }

                override fun connectStart(
                    call: Call,
                    inetSocketAddress: java.net.InetSocketAddress,
                    proxy: java.net.Proxy,
                ) {
                    recordEvent(call, "CONNECT_START")
                    if (isInventoryCall(call)) {
                        val address = inetSocketAddress.address
                        val connectionTarget = if (
                            inetSocketAddress.port == expectedOrigin.port && address?.isLoopbackAddress == true
                        ) {
                            "CONNECT_LOCAL_MATCH"
                        } else {
                            "CONNECT_LOCAL_MISMATCH"
                        }
                        recordEvent(call, connectionTarget)
                    }
                }

                override fun connectEnd(
                    call: Call,
                    inetSocketAddress: java.net.InetSocketAddress,
                    proxy: java.net.Proxy,
                    protocol: okhttp3.Protocol?,
                ) {
                    recordEvent(call, "CONNECT_END")
                }

                override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
                    recordEvent(call, "CONNECTION_ACQUIRED")
                }

                override fun requestHeadersEnd(call: Call, request: Request) {
                    recordEvent(call, "REQUEST_SENT", request)
                }

                override fun responseHeadersStart(call: Call) {
                    recordEvent(call, "RESPONSE_START")
                }

                override fun callEnd(call: Call) {
                    recordEvent(call, "END")
                    if (isInventoryCall(call) && inventoryCallOutcome.get() == "STARTED") {
                        inventoryCallOutcome.set("COMPLETED")
                    }
                }

                override fun responseHeadersEnd(call: Call, response: Response) {
                    recordEvent(call, "HTTP_${response.code}", response.request)
                    if (isInventoryCall(call)) {
                        inventoryCallOutcome.set("HTTP_${response.code}")
                    }
                }

                override fun cacheHit(call: Call, response: Response) {
                    recordEvent(call, "CACHE_HIT")
                }

                override fun cacheMiss(call: Call) {
                    recordEvent(call, "CACHE_MISS")
                }

                override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
                    recordEvent(call, "CACHE_CONDITIONAL_HIT")
                }

                override fun satisfactionFailure(call: Call, response: Response) {
                    recordEvent(call, "CACHE_FAILURE_HTTP_${response.code}")
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    val category = failureCategory(ioe)
                    recordEvent(call, "FAIL_$category")
                    if (isInventoryCall(call)) {
                        val exceptionClass = when (ioe) {
                            is UnknownHostException -> "UnknownHostException"
                            is ConnectException -> "ConnectException"
                            is SocketTimeoutException -> "SocketTimeoutException"
                            else -> "IOException"
                        }
                        inventoryCallOutcome.set("$exceptionClass:$category")
                    }
                }
            }
        }
        .build()
}
