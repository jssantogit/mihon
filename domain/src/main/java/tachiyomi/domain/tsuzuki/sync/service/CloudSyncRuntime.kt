package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport

interface CloudSyncRuntime {
    val state: StateFlow<SyncRuntimeState>

    suspend fun run(trigger: SyncTrigger): SyncCycleReport

    suspend fun resolveConflict(
        conflict: SyncConflict,
        choice: SyncConflictResolutionChoice,
    ): SyncConflictResolutionResult
}
