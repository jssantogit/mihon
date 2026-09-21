package eu.kanade.tachiyomi.ui.tsuzuki.sync

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.StoredSyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor
import tachiyomi.domain.tsuzuki.sync.model.SyncConflict
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionChoice
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictResolutionResult
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictValue
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.repository.SyncConflictRepository
import tachiyomi.domain.tsuzuki.sync.service.CloudSyncRuntime
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiSyncScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `logged out diagnostics show cloud off and sync now does not run runtime`() = runTest(dispatcher) {
        val account = FakeAccountRepository(AccountState.LoggedOut)
        val runtime = FakeCloudSyncRuntime()
        val model = TsuzukiSyncScreenModel(
            accountRepository = account,
            runtime = runtime,
            stateStore = FakeStateStore(),
            conflictRepository = FakeConflictRepository(),
        )

        advanceUntilIdle()
        model.state.value.cloudEnabled shouldBe false

        model.syncNow()
        advanceUntilIdle()

        runtime.runCalls shouldBe 0
        model.state.value.error shouldBe TsuzukiSyncScreenError.AUTHORIZATION_REQUIRED
    }

    @Test
    fun `authenticated sync now runs manual runtime and refreshes diagnostics`() = runTest(dispatcher) {
        val account = FakeAccountRepository(
            AccountState.Authenticated(
                TsuzukiAccount("user-1", "reader@example.com"),
            ),
        )
        val runtime = FakeCloudSyncRuntime()
        val stateStore = FakeStateStore().apply {
            cursors[SyncDocumentKind.LIBRARY] = SupabaseSyncCursor(
                documentKind = SyncDocumentKind.LIBRARY,
                eventCursor = 5,
                lastSuccessfulSyncAtEpochMillis = 1234,
            )
        }
        val model = TsuzukiSyncScreenModel(
            accountRepository = account,
            runtime = runtime,
            stateStore = stateStore,
            conflictRepository = FakeConflictRepository(),
        )

        advanceUntilIdle()
        model.syncNow()
        advanceUntilIdle()

        runtime.runCalls shouldBe 1
        runtime.lastTrigger shouldBe SyncTrigger.MANUAL
        model.state.value.lastSuccessfulSyncAtEpochMillis shouldBe 1234
        model.state.value.error shouldBe null
    }

    @Test
    fun `conflict choice is forwarded exactly as selected`() = runTest(dispatcher) {
        val account = FakeAccountRepository(
            AccountState.Authenticated(
                TsuzukiAccount("user-1", "reader@example.com"),
            ),
        )
        val conflict = StoredSyncConflict(
            conflict = SyncConflict(
                documentKind = SyncDocumentKind.LIBRARY,
                recordId = "title-1",
                propertyPath = listOf("status"),
                kind = SyncConflictKind.FIELD_DIVERGENCE,
                base = SyncConflictValue.Missing,
                local = SyncConflictValue.Present(JsonPrimitive("READING")),
                remote = SyncConflictValue.Present(JsonPrimitive("COMPLETED")),
                remoteConflictId = 7,
            ),
            createdAtEpochMillis = 1,
        )
        val conflicts = FakeConflictRepository().apply {
            values[SyncDocumentKind.LIBRARY] = listOf(conflict)
        }
        val runtime = FakeCloudSyncRuntime()
        val model = TsuzukiSyncScreenModel(
            accountRepository = account,
            runtime = runtime,
            stateStore = FakeStateStore(),
            conflictRepository = conflicts,
        )

        advanceUntilIdle()
        model.resolveConflict(
            stored = conflict,
            choice = SyncConflictResolutionChoice.KEEP_REMOTE,
        )
        advanceUntilIdle()

        runtime.resolveCalls shouldBe listOf(
            conflict.conflict to SyncConflictResolutionChoice.KEEP_REMOTE,
        )
    }

    private class FakeAccountRepository(
        initial: AccountState,
    ) : AccountRepository {
        private val mutable = MutableStateFlow(initial)
        override val state: StateFlow<AccountState> = mutable

        override suspend fun register(email: String, password: String) = error("not used")
        override suspend fun login(email: String, password: String) = error("not used")
        override suspend fun logout() = error("not used")
        override suspend fun sendPasswordRecovery(email: String) = error("not used")
        override suspend fun refreshSession() = Result.success(mutable.value)
        override suspend fun getAccessToken() = Result.success(null)
    }

    private class FakeCloudSyncRuntime : CloudSyncRuntime {
        private val mutableState = MutableStateFlow<SyncRuntimeState>(SyncRuntimeState.Idle)
        override val state: StateFlow<SyncRuntimeState> = mutableState

        var runCalls = 0
        var lastTrigger: SyncTrigger? = null
        val resolveCalls = mutableListOf<Pair<SyncConflict, SyncConflictResolutionChoice>>()

        override suspend fun run(trigger: SyncTrigger): SyncCycleReport {
            runCalls += 1
            lastTrigger = trigger
            return SyncCycleReport(emptyList())
        }

        override suspend fun resolveConflict(
            conflict: SyncConflict,
            choice: SyncConflictResolutionChoice,
        ): SyncConflictResolutionResult {
            resolveCalls += conflict to choice
            return SyncConflictResolutionResult.Resolved
        }
    }

    private class FakeStateStore : SupabaseSyncStateStore {
        val cursors = mutableMapOf<SyncDocumentKind, SupabaseSyncCursor>()
        private val pending = mutableMapOf<SyncDocumentKind, SupabasePendingMutation>()

        override suspend fun getCursor(documentKind: SyncDocumentKind) =
            cursors[documentKind]

        override suspend fun putCursor(
            documentKind: SyncDocumentKind,
            eventCursor: Long,
            lastSuccessfulSyncAtEpochMillis: Long?,
        ) {
            cursors[documentKind] = SupabaseSyncCursor(
                documentKind = documentKind,
                eventCursor = eventCursor,
                lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAtEpochMillis,
            )
        }

        override suspend fun getPending(documentKind: SyncDocumentKind) =
            pending[documentKind]

        override suspend fun putPending(mutation: SupabasePendingMutation) {
            pending[mutation.batch.documentKind] = mutation
        }

        override suspend fun recordPendingFailure(
            mutation: SupabasePendingMutation,
            nextAttemptAtEpochMillis: Long?,
        ) {
            pending[mutation.batch.documentKind] = mutation.copy(
                attemptCount = mutation.attemptCount + 1,
                nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            )
        }

        override suspend fun deletePending(mutationId: String) {
            pending.entries.removeAll { it.value.batch.mutationId == mutationId }
        }
    }

    private class FakeConflictRepository : SyncConflictRepository {
        val values = mutableMapOf<SyncDocumentKind, List<StoredSyncConflict>>()

        override suspend fun replaceForDocument(
            documentKind: SyncDocumentKind,
            conflicts: List<SyncConflict>,
            createdAtEpochMillis: Long,
        ) {
            values[documentKind] = conflicts.map {
                StoredSyncConflict(it, createdAtEpochMillis)
            }
        }

        override suspend fun getForDocument(
            documentKind: SyncDocumentKind,
        ): List<StoredSyncConflict> = values[documentKind].orEmpty()

        override suspend fun clearForDocument(documentKind: SyncDocumentKind) {
            values.remove(documentKind)
        }
    }
}
