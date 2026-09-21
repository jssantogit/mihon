package eu.kanade.tachiyomi.data.tsuzuki.supabase

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import tachiyomi.data.tsuzuki.sync.CollectionsSyncAdapter
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSyncIntentRepository
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.repository.ChapterOverrideRepository
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.sync.adapter.AddonStateSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.CanonicalLibrarySyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.CanonicalReadingProgressSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.CanonicalTitlesSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.ChapterOverridesSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.ChapterUpdateStateSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.ContentPreferencesSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.ContinueReadingStateSyncAdapter
import tachiyomi.domain.tsuzuki.sync.adapter.IntegrationSettingsSyncAdapter
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncOutboxRepository
import tachiyomi.domain.tsuzuki.sync.repository.SyncStateRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentitySyncRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleMergePort
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository
import tachiyomi.domain.tsuzuki.sync.service.CloudSyncRuntime
import tachiyomi.domain.tsuzuki.sync.service.SupabaseConflictResolver
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncOrchestrator
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeController
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SupabaseSyncRuntime(
    context: Context,
    networkHelper: NetworkHelper,
    json: Json,
    accountRepository: AccountRepository,
    outboxRepository: SyncOutboxRepository,
    stateRepository: SyncStateRepository,
    supabaseStateStore: SupabaseSyncStateStore,
    conflictRepository: SyncConflictRepository,
    identityRepository: CanonicalIdentitySyncRepository,
    canonicalTitleMergePort: CanonicalTitleMergePort,
    titleSource: CanonicalTitleSyncSource,
    titleRepository: CanonicalTitleRepository,
    libraryRepository: CanonicalLibraryRepository,
    chapterRepository: CanonicalChapterRepository,
    readingRepository: CanonicalReadingRepository,
    evidenceRepository: ChapterSyncEvidenceRepository,
    chapterUpdateStateRepository: ChapterUpdateStateRepository,
    continueReadingVisibilityRepository: ContinueReadingVisibilityRepository,
    collectionStore: CollectionStore,
    chapterOverrideRepository: ChapterOverrideRepository,
    readerPreferenceRepository: CanonicalReaderPreferenceRepository,
    integrationSettingsRepository: IntegrationSettingsRepository,
    contentPreferenceRepository: ContentPreferenceRepository,
    canonicalReaderPreferences: CanonicalReaderPreferences,
    addonRepository: AddonRepository,
    addonSyncIntentRepository: AddonSyncIntentRepository,
) : CloudSyncRuntime {

    private val clock: SyncClock = AndroidSupabaseSyncClock
    private val clientIdentity = SyncClientIdentityStore(context)
    private val revisionSource: SyncRevisionSource = AndroidSupabaseRevisionSource(
        clientIdentity = clientIdentity,
    )
    private val transport = SupabaseSyncHttpTransport(
        client = supabaseSafeClient(networkHelper.client),
        configuration = SupabaseConfiguration.fromBuildConfig(),
        accountRepository = accountRepository,
        json = json,
    )
    private val adapters = listOf(
        CanonicalTitlesSyncAdapter(
            titleSource = titleSource,
            identitySource = identityRepository,
            titleRepository = titleRepository,
            canonicalTitleMergePort = canonicalTitleMergePort,
            revisionSource = revisionSource,
            clock = clock,
        ),
        CanonicalLibrarySyncAdapter(
            libraryRepository = libraryRepository,
            revisionSource = revisionSource,
            clock = clock,
        ),
        CanonicalReadingProgressSyncAdapter(
            titleSource = titleSource,
            chapterRepository = chapterRepository,
            readingRepository = readingRepository,
            evidenceRepository = evidenceRepository,
            revisionSource = revisionSource,
            clock = clock,
        ),
        ChapterUpdateStateSyncAdapter(
            stateRepository = chapterUpdateStateRepository,
            chapterRepository = chapterRepository,
            evidenceRepository = evidenceRepository,
            revisionSource = revisionSource,
            clock = clock,
        ),
        ContinueReadingStateSyncAdapter(
            visibilityRepository = continueReadingVisibilityRepository,
            revisionSource = revisionSource,
            clock = clock,
        ),
        CollectionsSyncAdapter(
            store = collectionStore,
            revisionSource = revisionSource,
            clock = clock,
        ),
        ChapterOverridesSyncAdapter(
            overrideRepository = chapterOverrideRepository,
            readerPreferenceRepository = readerPreferenceRepository,
            revisionSource = revisionSource,
            clock = clock,
        ),
        IntegrationSettingsSyncAdapter(
            repository = integrationSettingsRepository,
            json = json,
            revisionSource = revisionSource,
            clock = clock,
        ),
        ContentPreferencesSyncAdapter(
            repository = contentPreferenceRepository,
            readerPreferences = canonicalReaderPreferences,
            revisionSource = revisionSource,
            clock = clock,
        ),
        AddonStateSyncAdapter(
            addonRepository = addonRepository,
            intentRepository = addonSyncIntentRepository,
            revisionSource = revisionSource,
            clock = clock,
        ),
    )

    private val orchestrator = SupabaseSyncOrchestrator(
        accountRepository = accountRepository,
        transport = transport,
        identityClaimTransport = transport,
        identityRepository = identityRepository,
        canonicalTitleMergePort = canonicalTitleMergePort,
        outboxRepository = outboxRepository,
        stateRepository = stateRepository,
        supabaseStateStore = supabaseStateStore,
        conflictRepository = conflictRepository,
        adapters = adapters,
        clientIdentityProvider = clientIdentity,
        clock = clock,
    )

    private val conflictResolver = SupabaseConflictResolver(
        accountRepository = accountRepository,
        transport = transport,
        outboxRepository = outboxRepository,
        stateRepository = stateRepository,
        supabaseStateStore = supabaseStateStore,
        conflictRepository = conflictRepository,
        adapters = adapters,
        clientIdentityProvider = clientIdentity,
        clock = clock,
    )

    private val controller = SyncRuntimeController(
        runner = orchestrator,
        clock = clock,
    )

    override val state: StateFlow<SyncRuntimeState>
        get() = controller.state

    override suspend fun run(trigger: SyncTrigger): SyncCycleReport =
        controller.run(trigger)

    override suspend fun resolveConflict(
        conflict: SyncConflict,
        choice: SyncConflictResolutionChoice,
    ): SyncConflictResolutionResult =
        conflictResolver.resolve(conflict, choice)
}

private data object AndroidSupabaseSyncClock : SyncClock {
    override fun nowEpochMillis(): Long =
        Clock.System.now().toEpochMilliseconds()
}

private class AndroidSupabaseRevisionSource(
    private val clientIdentity: SyncClientIdentityStore,
) : SyncRevisionSource {

    override val deviceId: String = clientIdentity.getOrCreate()

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
