package eu.kanade.tachiyomi.data.tsuzuki.drivesync

import android.content.Context
import android.provider.Settings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import tachiyomi.data.tsuzuki.sync.CollectionsSyncAdapter
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.sync.adapter.CanonicalLibrarySyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.SourceMappingsSyncAdapter
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.DriveSyncTransport
import tachiyomi.domain.tsuzuki.sync.service.KotlinxSyncDocumentCodec
import tachiyomi.domain.tsuzuki.sync.service.KotlinxSyncManifestCodec
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncCycleOrchestrator
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeController
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger
import tachiyomi.domain.tsuzuki.sync.service.ThreeWaySyncMerger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
class DriveSyncRuntime(
    context: Context,
    transport: DriveSyncTransport,
    outboxRepository: SyncOutboxRepository,
    stateRepository: SyncStateRepository,
    conflictRepository: SyncConflictRepository,
    libraryRepository: CanonicalLibraryRepository,
    titleRepository: CanonicalTitleRepository,
    mappingRepository: SourceTitleMappingRepository,
    sourcePreferenceRepository: ReadingSourcePreferenceRepository,
    collectionStore: CollectionStore,
    json: Json,
) {

    private val clock: SyncClock = AndroidSyncClock
    private val revisionSource: SyncRevisionSource = AndroidSyncRevisionSource(context)
    private val controller = SyncRuntimeController(
        runner = SyncCycleOrchestrator(
            transport = transport,
            outboxRepository = outboxRepository,
            stateRepository = stateRepository,
            conflictRepository = conflictRepository,
            adapters = listOf(
                CanonicalLibrarySyncAdapter(
                    libraryRepository = libraryRepository,
                    titleRepository = titleRepository,
                    revisionSource = revisionSource,
                    clock = clock,
                ),
                SourceMappingsSyncAdapter(
                    mappingRepository = mappingRepository,
                    preferenceRepository = sourcePreferenceRepository,
                    titleRepository = titleRepository,
                    revisionSource = revisionSource,
                    clock = clock,
                ),
                CollectionsSyncAdapter(
                    store = collectionStore,
                    revisionSource = revisionSource,
                    clock = clock,
                ),
            ),
            codec = KotlinxSyncDocumentCodec(json),
            manifestCodec = KotlinxSyncManifestCodec(json),
            merger = ThreeWaySyncMerger(),
            revisionSource = revisionSource,
            clock = clock,
        ),
        clock = clock,
    )

    val state: StateFlow<SyncRuntimeState>
        get() = controller.state

    suspend fun run(trigger: SyncTrigger): SyncCycleReport = controller.run(trigger)
}

private data object AndroidSyncClock : SyncClock {
    override fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

private class AndroidSyncRevisionSource(
    context: Context,
) : SyncRevisionSource {

    private val deviceId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    )
        ?.takeIf(String::isNotBlank)
        ?.let { "android:$it" }
        ?: "android:${context.packageName}"

    private val sequence = AtomicLong(
        Clock.System.now().toEpochMilliseconds().coerceAtLeast(0L),
    )

    override fun nextRevision(): SyncRevision {
        val now = Clock.System.now().toEpochMilliseconds().coerceAtLeast(0L)
        val next = sequence.updateAndGet { current ->
            maxOf(current + 1L, now)
        }
        return SyncRevision(
            deviceId = deviceId,
            sequence = next,
        )
    }
}
