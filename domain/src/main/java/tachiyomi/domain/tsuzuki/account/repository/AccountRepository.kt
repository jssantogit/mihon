package tachiyomi.domain.tsuzuki.account.repository

import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.tsuzuki.account.model.AccountState

interface AccountRepository {
    val state: StateFlow<AccountState>

    suspend fun register(email: String, password: String): Result<AccountState>

    suspend fun login(email: String, password: String): Result<AccountState>

    suspend fun logout(): Result<Unit>

    suspend fun sendPasswordRecovery(email: String): Result<Unit>

    suspend fun refreshSession(): Result<AccountState>

    suspend fun getAccessToken(): Result<String?>
}
