package eu.kanade.tachiyomi.ui.tsuzuki.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository

@Immutable
data class TsuzukiAccountScreenState(
    val accountState: AccountState = AccountState.LoggedOut,
    val isWorking: Boolean = false,
    val error: TsuzukiAccountScreenError? = null,
    val recoverySentTo: String? = null,
)

enum class TsuzukiAccountScreenError {
    INVALID_INPUT,
    AUTHENTICATION_FAILED,
    NETWORK_UNAVAILABLE,
    CONFIGURATION_UNAVAILABLE,
    UNKNOWN,
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiAccountScreenModel(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val isWorking = MutableStateFlow(false)
    private val error = MutableStateFlow<TsuzukiAccountScreenError?>(null)
    private val recoverySentTo = MutableStateFlow<String?>(null)

    val state: StateFlow<TsuzukiAccountScreenState> = combine(
        accountRepository.state,
        isWorking,
        error,
        recoverySentTo,
    ) { accountState, working, currentError, recovery ->
        TsuzukiAccountScreenState(
            accountState = accountState,
            isWorking = working,
            error = currentError,
            recoverySentTo = recovery,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiAccountScreenState(
            accountState = accountRepository.state.value,
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
}
