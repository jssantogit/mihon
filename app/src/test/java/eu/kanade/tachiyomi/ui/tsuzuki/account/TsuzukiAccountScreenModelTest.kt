package eu.kanade.tachiyomi.ui.tsuzuki.account

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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiAccountScreenModelTest {

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
    fun `logged out account is a non blocking local only state`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = TsuzukiAccountScreenModel(repository)

        advanceUntilIdle()

        model.state.value.accountState shouldBe AccountState.LoggedOut
        model.state.value.isWorking shouldBe false
        repository.loginCalls shouldBe 0
        repository.registerCalls shouldBe 0
    }

    @Test
    fun `invalid credentials are rejected before backend call`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = TsuzukiAccountScreenModel(repository)

        model.login("not-an-email", "")
        advanceUntilIdle()

        model.state.value.error shouldBe TsuzukiAccountScreenError.INVALID_INPUT
        repository.loginCalls shouldBe 0
    }

    @Test
    fun `login and logout update optional cloud account state`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = TsuzukiAccountScreenModel(repository)

        model.login("reader@example.com", "secret")
        advanceUntilIdle()

        model.state.value.accountState shouldBe AccountState.Authenticated(
            TsuzukiAccount(
                id = "user-1",
                email = "reader@example.com",
            ),
        )
        repository.loginCalls shouldBe 1

        model.logout()
        advanceUntilIdle()

        model.state.value.accountState shouldBe AccountState.LoggedOut
        repository.logoutCalls shouldBe 1
    }

    private class FakeAccountRepository : AccountRepository {
        private val mutableState = MutableStateFlow<AccountState>(AccountState.LoggedOut)
        override val state: StateFlow<AccountState> = mutableState

        var loginCalls = 0
        var registerCalls = 0
        var logoutCalls = 0

        override suspend fun register(
            email: String,
            password: String,
        ): Result<AccountState> {
            registerCalls += 1
            val next = AccountState.EmailConfirmationRequired(email)
            mutableState.value = next
            return Result.success(next)
        }

        override suspend fun login(
            email: String,
            password: String,
        ): Result<AccountState> {
            loginCalls += 1
            val next = AccountState.Authenticated(
                TsuzukiAccount(
                    id = "user-1",
                    email = email,
                ),
            )
            mutableState.value = next
            return Result.success(next)
        }

        override suspend fun logout(): Result<Unit> {
            logoutCalls += 1
            mutableState.value = AccountState.LoggedOut
            return Result.success(Unit)
        }

        override suspend fun sendPasswordRecovery(email: String): Result<Unit> =
            Result.success(Unit)

        override suspend fun refreshSession(): Result<AccountState> =
            Result.success(mutableState.value)

        override suspend fun getAccessToken(): Result<String?> =
            Result.success(null)
    }
}
