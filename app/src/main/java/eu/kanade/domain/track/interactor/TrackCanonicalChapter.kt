package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.tsuzuki.reader.interactor.GetCanonicalTrackerProgress
import tachiyomi.domain.tsuzuki.reader.interactor.ResolveCanonicalTrackerBindings

@Inject
class TrackCanonicalChapter(
    private val getCanonicalTrackerProgress: GetCanonicalTrackerProgress,
    private val resolveCanonicalTrackerBindings: ResolveCanonicalTrackerBindings,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    suspend fun await(
        context: Context,
        canonicalChapterId: String,
        setupJobOnFailure: Boolean = true,
    ) {
        withNonCancellableContext {
            val progress = getCanonicalTrackerProgress.execute(canonicalChapterId)
                ?: return@withNonCancellableContext
            val resolution = resolveCanonicalTrackerBindings.execute(progress.canonicalTitleId)

            resolution.conflictingTrackerIds.forEach { trackerId ->
                logcat(LogPriority.WARN) {
                    "Skipping canonical tracker $trackerId because source mappings disagree on remote identity"
                }
            }

            if (resolution.tracks.isEmpty()) return@withNonCancellableContext

            resolution.tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    progress.chapterNumber <= track.lastChapterRead
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val updatedTrack = service.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastChapterRead = progress.chapterNumber)
                            service.update(updatedTrack.toDbTrack(), true)
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(track.id)
                        } catch (error: Exception) {
                            delayedTrackingStore.add(track.id, progress.chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw error
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { error ->
                    logcat(LogPriority.WARN, error) {
                        "Canonical tracker update failed for chapter $canonicalChapterId"
                    }
                }
        }
    }
}
