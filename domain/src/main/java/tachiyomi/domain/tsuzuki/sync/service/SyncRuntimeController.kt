package tachiyomi.domain.tsuzuki.sync.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.sync.model.SyncCycleReport
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason

fun interface SyncCycleRunner {
    suspend fun runOnce(): SyncCycleReport
}

enum class SyncTrigger {
    MANUAL,
    BACKGROUND,
}

sealed interface SyncRuntimeState {
    data object Idle : SyncRuntimeState

    data class Running(
        val trigger: SyncTrigger,
        val startedAtEpochMillis: Long,
    ) : SyncRuntimeState

    data class Completed(
        val trigger: SyncTrigger,
        val startedAtEpochMillis: Long,
        val completedAtEpochMillis: Long,
        val report: SyncCycleReport,
    ) : SyncRuntimeState

    data class Failed(
        val trigger: SyncTrigger,
        val startedAtEpochMillis: Long,
        val completedAtEpochMillis: Long,
        val failure: SyncFailure,
    ) : SyncRuntimeState
}

class SyncRuntimeController(
    private val runner: SyncCycleRunner,
    private val clock: SyncClock,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SyncRuntimeState>(SyncRuntimeState.Idle)

    val state: StateFlow<SyncRuntimeState> = mutableState.asStateFlow()

    suspend fun run(trigger: SyncTrigger): SyncCycleReport = mutex.withLock {
        val startedAt = clock.nowEpochMillis()
        mutableState.value = SyncRuntimeState.Running(
            trigger = trigger,
            startedAtEpochMillis = startedAt,
        )

        try {
            val report = runner.runOnce()
            mutableState.value = SyncRuntimeState.Completed(
                trigger = trigger,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = clock.nowEpochMillis(),
                report = report,
            )
            report
        } catch (e: CancellationException) {
            mutableState.value = SyncRuntimeState.Idle
            throw e
        } catch (e: Throwable) {
            mutableState.value = SyncRuntimeState.Failed(
                trigger = trigger,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = clock.nowEpochMillis(),
                failure = SyncFailure(SyncFailureReason.UNKNOWN),
            )
            throw e
        }
    }
}
