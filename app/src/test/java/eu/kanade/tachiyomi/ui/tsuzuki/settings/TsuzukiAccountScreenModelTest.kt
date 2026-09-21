package eu.kanade.tachiyomi.ui.tsuzuki.settings

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
    fun `logged out state keeps local use available`() = runTest(dispatcher) {
        val repository = FakeAccountRepository(AccountState.LoggedOut)
        val model = TsuzukiAccountScreenModel(repository)

        advanceUntilIdle()

        model.state.value.accountState shouldBe AccountState.LoggedOut
        model.state.value.localUseAvailable shouldBe true
    }

    @Test
    fun `login uses email password and exposes authenticated account`() = runTest(dispatcher) {
        val repository = FakeAccountRepository(AccountState.LoggedOut)
        val model = TsuzukiAccountScreenModel(repository)

        model.login("reader@example.com", "secret123")
        advanceUntilIdle()

        repository.lastLogin shouldBe "reader@example.com" to "secret123"
        model.state.value.accountState shouldBe AccountState.Authenticated(
            TsuzukiAccount("user-1", "reader@example.com"),
        )
    }

    @Test
    fun `invalid form never calls account backend`() = runTest(dispatcher) {
        val repository = FakeAccountRepository(AccountState.LoggedOut)
        val model = TsuzukiAccountScreenModel(repository)

        model.createAccount("invalid", "123")
        advanceUntilIdle()

        repository.registerCalls shouldBe 0
        model.state.value.error shouldBe "Enter a valid email address"
    }

    private class FakeAccountRepository(
        initial: AccountState,
    ) : AccountRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AccountState> = mutableState

        var lastLogin: Pair<String, String>? = null
        var registerCalls = 0

        override suspend fun register(email: String, password: String): Result<AccountState> {
            registerCalls += 1
            val next = AccountState.EmailConfirmationRequired(email)
            mutableState.value = next
            return Result.success(next)
        }

        override suspend fun login(email: String, password: String): Result<AccountState> {
            lastLogin = email to password
            val next = AccountState.Authenticated(
                TsuzukiAccount("user-1", email),
            )
            mutableState.value = next
            return Result.success(next)
        }

        override suspend fun logout(): Result<Unit> {
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
