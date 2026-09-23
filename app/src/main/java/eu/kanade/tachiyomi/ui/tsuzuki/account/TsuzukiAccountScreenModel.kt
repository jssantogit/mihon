package eu.kanade.tachiyomi.ui.tsuzuki.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.service.CloudSyncRuntime
import tachiyomi.domain.tsuzuki.sync.service.SyncRuntimeState
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger
import java.io.IOException

@Immutable
data class TsuzukiAccountScreenState(
    val accountState: AccountState = AccountState.LoggedOut,
    val isWorking: Boolean = false,
    val error: TsuzukiAccountScreenError? = null,
    val recoverySentTo: String? = null,
    val syncRuntimeState: SyncRuntimeState = SyncRuntimeState.Idle,
) {
    val isSyncing: Boolean
        get() = syncRuntimeState is SyncRuntimeState.Running
}

enum class TsuzukiAccountScreenError {
    INVALID_INPUT,
    AUTHENTICATION_FAILED,
    NETWORK_UNAVAILABLE,
    CONFIGURATION_UNAVAILABLE,
    SYNC_FAILED,
    UNKNOWN,
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiAccountScreenModel(
    private val accountRepository: AccountRepository,
    private val cloudSyncRuntime: CloudSyncRuntime,
) : ViewModel() {

    private val isWorking = MutableStateFlow(false)
    private val error = MutableStateFlow<TsuzukiAccountScreenError?>(null)
    private val recoverySentTo = MutableStateFlow<String?>(null)

    private val localUiState = combine(
        isWorking,
        error,
        recoverySentTo,
    ) { working, currentError, recovery ->
        LocalUiState(
            isWorking = working,
            error = currentError,
            recoverySentTo = recovery,
        )
    }

    val state: StateFlow<TsuzukiAccountScreenState> = combine(
        accountRepository.state,
        cloudSyncRuntime.state,
        localUiState,
    ) { accountState, syncRuntimeState, local ->
        TsuzukiAccountScreenState(
            accountState = accountState,
            isWorking = local.isWorking,
            error = local.error,
            recoverySentTo = local.recoverySentTo,
            syncRuntimeState = syncRuntimeState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiAccountScreenState(
            accountState = accountRepository.state.value,
            syncRuntimeState = cloudSyncRuntime.state.value,
        ),
    )

    fun login(email: String, password: String) {
        val normalizedEmail = email.trim()
        if (!validCredentials(normalizedEmail, password)) {
            error.value = TsuzukiAccountScreenError.INVALID_INPUT
            return
        }
        launchOperation {
            accountRepository.login(normalizedEmail, password)
                .getOrThrow()
        }
    }

    fun register(email: String, password: String) {
        val normalizedEmail = email.trim()
        if (!validCredentials(normalizedEmail, password)) {
            error.value = TsuzukiAccountScreenError.INVALID_INPUT
            return
        }
        launchOperation {
            accountRepository.register(normalizedEmail, password)
                .getOrThrow()
        }
    }

    fun sendPasswordRecovery(email: String) {
        val normalizedEmail = email.trim()
        if (!validEmail(normalizedEmail)) {
            error.value = TsuzukiAccountScreenError.INVALID_INPUT
            return
        }
        launchOperation(
            onSuccess = {
                recoverySentTo.value = normalizedEmail
            },
        ) {
            accountRepository.sendPasswordRecovery(normalizedEmail)
                .getOrThrow()
        }
    }

    fun logout() {
        launchOperation {
            accountRepository.logout().getOrThrow()
        }
    }

    fun syncNow() {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            error.value = TsuzukiAccountScreenError.AUTHENTICATION_FAILED
            return
        }
        if (isWorking.value || cloudSyncRuntime.state.value is SyncRuntimeState.Running) return

        viewModelScope.launch {
            error.value = null
            try {
                val report = cloudSyncRuntime.run(SyncTrigger.MANUAL)
                val failure = report.globalFailure
                    ?: report.documentResults
                        .filterIsInstance<SyncDocumentResult.Failed>()
                        .firstOrNull()
                        ?.failure
                error.value = failure?.reason?.toAccountScreenError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error.value = TsuzukiAccountScreenError.SYNC_FAILED
            }
        }
    }

    fun clearFeedback() {
        error.value = null
        recoverySentTo.value = null
    }

    private fun launchOperation(
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (isWorking.value) return

        viewModelScope.launch {
            isWorking.value = true
            error.value = null
            recoverySentTo.value = null
            try {
                block()
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error.value = failure.toScreenError()
            } finally {
                isWorking.value = false
            }
        }
    }

    private fun validCredentials(email: String, password: String): Boolean {
        return validEmail(email) && password.isNotBlank()
    }

    private fun validEmail(email: String): Boolean {
        val separator = email.indexOf('@')
        return separator > 0 && separator < email.lastIndex
    }

    private fun SyncFailureReason.toAccountScreenError(): TsuzukiAccountScreenError = when (this) {
        SyncFailureReason.AUTHORIZATION_REQUIRED ->
            TsuzukiAccountScreenError.AUTHENTICATION_FAILED
        SyncFailureReason.NETWORK_UNAVAILABLE,
        SyncFailureReason.RATE_LIMITED,
        SyncFailureReason.REMOTE_UNAVAILABLE,
        SyncFailureReason.REMOTE_ACCESS_DENIED,
        SyncFailureReason.REMOTE_NOT_FOUND,
        -> TsuzukiAccountScreenError.NETWORK_UNAVAILABLE
        else -> TsuzukiAccountScreenError.SYNC_FAILED
    }

    private fun Throwable.toScreenError(): TsuzukiAccountScreenError {
        if (this is IOException) {
            return TsuzukiAccountScreenError.NETWORK_UNAVAILABLE
        }

        val message = message.orEmpty().lowercase()
        return when {
            "not configured" in message ||
                "supabase url" in message ||
                "publishable" in message -> {
                TsuzukiAccountScreenError.CONFIGURATION_UNAVAILABLE
            }
            "http 400" in message ||
                "http 401" in message ||
                "http 403" in message ||
                "http 422" in message -> {
                TsuzukiAccountScreenError.AUTHENTICATION_FAILED
            }
            "network" in message ||
                "timeout" in message ||
                "connection" in message -> {
                TsuzukiAccountScreenError.NETWORK_UNAVAILABLE
            }
            else -> TsuzukiAccountScreenError.UNKNOWN
        }
    }

    private data class LocalUiState(
        val isWorking: Boolean,
        val error: TsuzukiAccountScreenError?,
        val recoverySentTo: String?,
    )
}
