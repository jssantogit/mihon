package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentPreferenceRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import java.util.concurrent.CountDownLatch

/**
 * Offline process recovery check for the migration-33 Reader projection queue. The host runner
 * force-stops the live phase-one process after this test reports its durable pending state.
 */
@RunWith(AndroidJUnit4::class)
class CanonicalReadingProcessRecoveryInstrumentedTest {

    @Test(timeout = 600_000L)
    fun failedAcknowledgementLeavesDurableProjectionForProcessRestart() {
        requireOptIn()
        val app = targetApp()
        val database = app.graph.database
        val driver = app.graph.sqlDriver
        val reading = CanonicalReadingRepositoryImpl(database)
        val fixture = seedFixture(database)

        assertFalse("Startup recovery fixture must not be incognito", app.graph.basePreferences.incognitoMode.get())
        assertEquals(EXPECTED_PROGRESS, runBlocking { reading.getProgress(CHAPTER_ID) })
        assertEquals(EXPECTED_HISTORY, runBlocking { reading.getHistory(CHAPTER_ID) })

        runBlocking {
            driver.execute(
                null,
                """
                CREATE TRIGGER $ACK_FAILURE_TRIGGER
                BEFORE DELETE ON tsuzuki_mihon_projection_queue
                WHEN OLD.canonical_chapter_id = '$CHAPTER_ID' AND OLD.mihon_chapter_id = ${fixture.mihonChapterId}
                BEGIN SELECT RAISE(ABORT, 'CR02 fixture failure before acknowledgement'); END
                """.trimIndent(),
                0,
            ).await()
        }

        try {
            assertEquals(0, runBlocking { reading.drainPendingProjections(limit = 1) })
            val failedQueueRow = requireNotNull(runBlocking { queueSnapshot(database, fixture.mihonChapterId) }) {
                "The failed acknowledgement must leave its outbox item pending"
            }
            assertTrue("Progress remains queued after the failed acknowledgement", failedQueueRow.pendingProgress)
            assertTrue("History remains queued after the failed acknowledgement", failedQueueRow.pendingHistory)
            assertEquals(HISTORY_DURATION, failedQueueRow.pendingDuration)
            assertEquals(1L, failedQueueRow.attemptCount)
            assertTrue(
                "The retry backoff should remain durable",
                failedQueueRow.nextRetryAt > System.currentTimeMillis(),
            )

            assertEquals(EXPECTED_PROGRESS, runBlocking { reading.getProgress(CHAPTER_ID) })
            assertEquals(EXPECTED_HISTORY, runBlocking { reading.getHistory(CHAPTER_ID) })
            val unprojectedChapter = runBlocking {
                database.chaptersQueries.getChapterById(fixture.mihonChapterId).awaitAsOne()
            }
            assertFalse("The failed transaction must roll back Mihon read state", unprojectedChapter.read)
            assertEquals(0L, unprojectedChapter.last_page_read)
            assertNull(
                "The failed transaction must roll back the additive Mihon history write",
                runBlocking {
                    database.historyQueries.getHistoryByMangaId(fixture.mangaId).awaitAsList().singleOrNull()
                },
            )
        } finally {
            runBlocking {
                driver.execute(null, "DROP TRIGGER IF EXISTS $ACK_FAILURE_TRIGGER", 0).await()
            }
        }

        runBlocking { awaitQueueDue(database, fixture.mihonChapterId) }
        report(
            phase = "PRE_RESTART_READY",
            facts = "queue=PENDING|ackFailure=INJECTED|retry=DUE|canonical=PRESERVED|legacy=ROLLED_BACK",
        )
        CountDownLatch(1).await()
    }

    @Test(timeout = 30_000L)
    fun newProcessStartupReplaysProjectionAndAcknowledgesExactlyOnce() {
        requireOptIn()
        val app = targetApp()
        assertFalse("Startup replay is disabled in incognito mode", app.graph.basePreferences.incognitoMode.get())

        val database = app.graph.database
        val reading = CanonicalReadingRepositoryImpl(database)
        val preferences = ContentPreferenceRepositoryImpl(database)
        val fixture = findFixture(database)
        val recovered = awaitRecovered(database, reading, preferences, fixture)

        assertEquals(EXPECTED_PROGRESS, recovered.progress)
        assertEquals(EXPECTED_HISTORY, recovered.history)
        assertEquals(EXPECTED_PREFERENCE, recovered.preference)
        val legacyChapter = requireNotNull(recovered.legacyChapter) {
            "Application startup did not restore the operational chapter"
        }
        assertTrue("Mihon progress should be replayed by application startup", legacyChapter.read)
        assertEquals(EXPECTED_PROGRESS.lastPageRead, legacyChapter.lastPageRead)
        assertEquals(HISTORY_DURATION, recovered.legacyHistoryDuration)
        assertNull("Successful replay must transactionally acknowledge the queue item", recovered.queueRow)
        assertEquals(
            "A second drain must not apply the additive history duration again",
            0,
            runBlocking { reading.drainPendingProjections(limit = 1) },
        )
        assertEquals(
            HISTORY_DURATION,
            runBlocking {
                database.historyQueries.getHistoryByMangaId(fixture.mangaId).awaitAsList().single().time_read
            },
        )

        report(
            phase = "POST_RESTART",
            facts = "queue=ACKNOWLEDGED|replay=STARTUP|preference=PRESERVED|progress=PRESERVED|history=ONCE",
        )
    }

    private fun seedFixture(database: Database): Fixture = runBlocking {
        val now = System.currentTimeMillis()
        val title = CanonicalTitle(
            id = TITLE_ID,
            displayTitle = "CR02 Offline Process Recovery Fixture",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = now,
            updatedAt = now,
        )
        val chapter = CanonicalChapter(
            id = CHAPTER_ID,
            canonicalTitleId = TITLE_ID,
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = now,
            updatedAt = now,
            confirmation = CanonicalChapterConfirmation.CONFIRMED,
        )
        CanonicalTitleRepositoryImpl(database).insert(title)
        CanonicalChapterRepositoryImpl(database).upsert(chapter)

        val manga = MangaRepositoryImpl(database).insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = SOURCE_ID,
                    url = MANGA_URL,
                    title = "CR02 Offline Process Recovery Fixture",
                    favorite = false,
                    dateAdded = now,
                    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                ),
            ),
        ).single()
        val mihonChapterId = database.chaptersQueries.insertReturningId(
            mangaId = manga.id,
            url = CHAPTER_URL,
            name = "Chapter 1",
            scanlator = null,
            read = false,
            bookmark = false,
            lastPageRead = 0L,
            chapterNumber = 1.0,
            sourceOrder = 0L,
            dateFetch = now,
            dateUpload = now,
            version = 0L,
            memo = kotlinx.serialization.json.buildJsonObject {},
        ).awaitAsOne()

        ContentPreferenceRepositoryImpl(database).upsert(EXPECTED_PREFERENCE)
        val reading = CanonicalReadingRepositoryImpl(database)
        reading.recordProgressWithProjection(EXPECTED_PROGRESS, mihonChapterId)
        reading.recordHistoryWithProjection(
            CanonicalChapterHistoryUpdate(
                canonicalChapterId = CHAPTER_ID,
                readAt = HISTORY_READ_AT,
                sessionReadDuration = HISTORY_DURATION,
            ),
            mihonChapterId,
        )
        Fixture(mangaId = manga.id, mihonChapterId = mihonChapterId)
    }

    private fun awaitRecovered(
        database: Database,
        reading: CanonicalReadingRepositoryImpl,
        preferences: ContentPreferenceRepositoryImpl,
        fixture: Fixture,
    ): RecoveredState {
        val deadline = SystemClock.elapsedRealtime() + STARTUP_RECOVERY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = runBlocking {
                val progress = reading.getProgress(CHAPTER_ID)
                val history = reading.getHistory(CHAPTER_ID)
                val preference = preferences.get(TITLE_ID)
                val queueRow = queueSnapshot(database, fixture.mihonChapterId)
                val legacyChapter = ChapterRepositoryImpl(database)
                    .getChapterByUrlAndMangaId(CHAPTER_URL, fixture.mangaId)
                val legacyHistory = database.historyQueries.getHistoryByMangaId(
                    fixture.mangaId,
                ).awaitAsList().singleOrNull()
                RecoveredState(
                    progress = progress,
                    history = history,
                    preference = preference,
                    queueRow = queueRow,
                    legacyChapter = legacyChapter,
                    legacyHistoryDuration = legacyHistory?.time_read,
                )
            }
            if (
                snapshot.queueRow == null &&
                snapshot.progress == EXPECTED_PROGRESS &&
                snapshot.history == EXPECTED_HISTORY &&
                snapshot.preference == EXPECTED_PREFERENCE &&
                snapshot.legacyChapter?.read == true &&
                snapshot.legacyChapter.lastPageRead == EXPECTED_PROGRESS.lastPageRead &&
                snapshot.legacyHistoryDuration == HISTORY_DURATION
            ) {
                return snapshot
            }
            SystemClock.sleep(50L)
        }
        throw AssertionError("Application startup did not replay and acknowledge the pending projection")
    }

    private fun findFixture(database: Database): Fixture = runBlocking {
        val manga = requireNotNull(MangaRepositoryImpl(database).getMangaByUrlAndSourceId(MANGA_URL, SOURCE_ID)) {
            "The phase-one manga fixture did not survive process restart"
        }
        val chapter = requireNotNull(ChapterRepositoryImpl(database).getChapterByUrlAndMangaId(CHAPTER_URL, manga.id)) {
            "The phase-one operational chapter did not survive process restart"
        }
        Fixture(mangaId = manga.id, mihonChapterId = chapter.id)
    }

    private suspend fun queueSnapshot(database: Database, mihonChapterId: Long): QueueSnapshot? =
        database.tsuzuki_mihon_projection_queueQueries.getTsuzukiMihonProjection(CHAPTER_ID, mihonChapterId) {
                _,
                _,
                pendingProgress,
                _,
                _,
                pendingHistory,
                pendingDuration,
                _,
                _,
                _,
                attemptCount,
                nextRetryAt,
            ->
            QueueSnapshot(pendingProgress, pendingHistory, pendingDuration, attemptCount, nextRetryAt)
        }.awaitAsOneOrNull()

    private suspend fun awaitQueueDue(database: Database, mihonChapterId: Long) {
        val deadline = SystemClock.elapsedRealtime() + RETRY_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val row = queueSnapshot(database, mihonChapterId)
            if (row != null && row.nextRetryAt <= System.currentTimeMillis()) return
            SystemClock.sleep(25L)
        }
        throw AssertionError("Failed projection did not remain available after its retry backoff")
    }

    private fun targetApp(): App {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(TARGET_PACKAGE, context.packageName)
        return context.applicationContext as App
    }

    private fun requireOptIn() {
        assumeTrue(
            "Use the dedicated disposable-emulator process-recovery runner",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "true",
        )
    }

    private fun report(phase: String, facts: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString(
                    "stream",
                    "CR02_PROCESS_RECOVERY|phase=$phase|pid=${Process.myPid()}|$facts",
                )
            },
        )
    }

    private data class Fixture(
        val mangaId: Long,
        val mihonChapterId: Long,
    )

    private data class QueueSnapshot(
        val pendingProgress: Boolean,
        val pendingHistory: Boolean,
        val pendingDuration: Long,
        val attemptCount: Long,
        val nextRetryAt: Long,
    )

    private data class RecoveredState(
        val progress: CanonicalChapterProgress?,
        val history: tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory?,
        val preference: ContentPreference?,
        val queueRow: QueueSnapshot?,
        val legacyChapter: Chapter?,
        val legacyHistoryDuration: Long?,
    )

    private companion object {
        const val TARGET_PACKAGE = "app.mihon.dev"
        const val OPT_IN_ARGUMENT = "cr02ProcessRecoveryOptIn"
        const val ACK_FAILURE_TRIGGER = "cr02_abort_projection_ack"
        const val TITLE_ID = "cr02-process-recovery-title"
        const val CHAPTER_ID = "cr02-process-recovery-chapter-1"
        const val SOURCE_ID = 9L
        const val MANGA_URL = "/cr02/process-recovery"
        const val CHAPTER_URL = "/cr02/process-recovery/chapter-1"
        const val HISTORY_READ_AT = 1_800_000_000_000L
        const val HISTORY_DURATION = 37_000L
        const val STARTUP_RECOVERY_TIMEOUT_MS = 15_000L
        const val RETRY_READY_TIMEOUT_MS = 10_000L

        val EXPECTED_PROGRESS = CanonicalChapterProgress(
            canonicalChapterId = CHAPTER_ID,
            read = true,
            lastPageRead = 6L,
            updatedAt = HISTORY_READ_AT,
        )
        val EXPECTED_HISTORY = tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory(
            canonicalChapterId = CHAPTER_ID,
            lastReadAt = HISTORY_READ_AT,
            totalReadDuration = HISTORY_DURATION,
        )
        val EXPECTED_PREFERENCE = ContentPreference(
            canonicalTitleId = TITLE_ID,
            preferredAddonId = AddonId("cr02.fixture.addon"),
            preferredLanguage = "en",
            updatedAt = HISTORY_READ_AT,
        )
    }
}
