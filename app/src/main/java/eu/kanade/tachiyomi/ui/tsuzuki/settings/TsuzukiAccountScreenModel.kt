package eu.kanade.tachiyomi.ui.tsuzuki.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
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
    val operation: AccountOperation? = null,
    val error: String? = null,
    val message: String? = null,
    val localUseAvailable: Boolean = true,
)

enum class AccountOperation {
    REGISTER,
    LOGIN,
    RECOVERY,
    LOGOUT,
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiAccountScreenModel(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val operation = MutableStateFlow<AccountOperation?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<TsuzukiAccountScreenState> = combine(
        accountRepository.state,
        operation,
        error,
        message,
    ) { account, activeOperation, currentError, currentMessage ->
        TsuzukiAccountScreenState(
            accountState = account,
            operation = activeOperation,
            error = currentError,
            message = currentMessage,
            localUseAvailable = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TsuzukiAccountScreenState(
            accountState = accountRepository.state.value,
        ),
    )

    fun createAccount(email: String, password: String) {
        val validated = validate(email, password) ?: return
        launch(AccountOperation.REGISTER) {
            accountRepository.register(validated.first, validated.second)
                .getOrThrow()
            "Account created. Check your email if confirmation is required."
        }
    }

    fun login(email: String, password: String) {
        val validated = validate(email, password) ?: return
        launch(AccountOperation.LOGIN) {
            accountRepository.login(validated.first, validated.second)
                .getOrThrow()
            "Signed in."
        }
    }

    fun sendRecovery(email: String) {
        val normalized = email.trim()
        if (!EMAIL_PATTERN.matches(normalized)) {
            error.value = "Enter a valid email address"
            message.value = null
            return
        }
        launch(AccountOperation.RECOVERY) {
            accountRepository.sendPasswordRecovery(normalized).getOrThrow()
            "Password recovery email sent."
        }
    }

    fun logout() {
        launch(AccountOperation.LOGOUT) {
            accountRepository.logout().getOrThrow()
            "Signed out. Local data remains available on this device."
        }
    }

    fun dismissFeedback() {
        error.value = null
        message.value = null
    }

    private fun validate(
        email: String,
        password: String,
    ): Pair<String, String>? {
        val normalizedEmail = email.trim()
        if (!EMAIL_PATTERN.matches(normalizedEmail)) {
            error.value = "Enter a valid email address"
            message.value = null
            return null
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            error.value = "Password must have at least ${MIN_PASSWORD_LENGTH} characters"
            message.value = null
            return null
        }
        return normalizedEmail to password
    }

    private fun launch(
        activeOperation: AccountOperation,
        block: suspend () -> String,
    ) {
        if (operation.value != null) return

        viewModelScope.launch {
            operation.value = activeOperation
            error.value = null
            message.value = null
            try {
                message.value = block()
            } catch (failure: Throwable) {
                error.value = failure.message ?: "Account operation failed"
            } finally {
                operation.value = null
            }
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
        val EMAIL_PATTERN = Regex(
            "^[A-Za-z0-9.!#$%&'*+/=?^_\x60{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$",
        )
    }
}
