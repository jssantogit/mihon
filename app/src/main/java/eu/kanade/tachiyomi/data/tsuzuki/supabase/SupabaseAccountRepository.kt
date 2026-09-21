package eu.kanade.tachiyomi.data.tsuzuki.supabase

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SupabaseAccountRepository(
    private val authService: SupabaseAuthService,
    private val sessionStore: SupabaseSessionStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : AccountRepository {

    constructor(
        context: Context,
        networkHelper: NetworkHelper,
        json: Json,
    ) : this(
        authService = SupabaseAuthService(
            client = supabaseSafeClient(networkHelper.client),
            configuration = SupabaseConfiguration.fromBuildConfig(),
            json = json,
        ),
        sessionStore = AndroidKeystoreSessionStore(context, json),
    )

    private val mutableState = MutableStateFlow(
        sessionStore.load()?.toAccountState() ?: AccountState.LoggedOut,
    )

    override val state: StateFlow<AccountState> = mutableState.asStateFlow()

    override suspend fun register(email: String, password: String): Result<AccountState> =
        runCatching {
            val result = authService.register(email, password)
            val next = result.session?.let {
                sessionStore.save(it)
                it.toAccountState()
            } ?: AccountState.EmailConfirmationRequired(email)
            mutableState.value = next
            next
        }

    override suspend fun login(email: String, password: String): Result<AccountState> =
        runCatching {
            val result = authService.login(email, password)
            val session = requireNotNull(result.session) {
                "Supabase login succeeded without a session"
            }
            sessionStore.save(session)
            session.toAccountState().also { mutableState.value = it }
        }

    override suspend fun logout(): Result<Unit> {
        val existing = sessionStore.load()
        sessionStore.clear()
        mutableState.value = AccountState.LoggedOut
        if (existing == null) return Result.success(Unit)
        return runCatching {
            authService.logout(existing.accessToken)
        }
    }

    override suspend fun sendPasswordRecovery(email: String): Result<Unit> =
        runCatching {
            authService.sendPasswordRecovery(email)
        }

    override suspend fun refreshSession(): Result<AccountState> =
        runCatching {
            val existing = sessionStore.load()
                ?: return@runCatching AccountState.LoggedOut.also {
                    mutableState.value = it
                }
            val result = authService.refresh(existing.refreshToken)
            val session = requireNotNull(result.session) {
                "Supabase refresh succeeded without a session"
            }
            sessionStore.save(session)
            session.toAccountState().also { mutableState.value = it }
        }

    override suspend fun getAccessToken(): Result<String?> = runCatching {
        if (state.value !is AccountState.Authenticated) return@runCatching null

        val session = sessionStore.load() ?: return@runCatching null
        if (session.expiresAtEpochSeconds > nowEpochSeconds() + ACCESS_TOKEN_REFRESH_SKEW_SECONDS) {
            return@runCatching session.accessToken
        }

        refreshSession().getOrThrow()
        sessionStore.load()?.accessToken
    }

    fun acceptSession(session: SupabaseSession): AccountState {
        sessionStore.save(session)
        return session.toAccountState().also { mutableState.value = it }
    }

    fun clearInvalidSession() {
        sessionStore.clear()
        mutableState.value = AccountState.LoggedOut
    }

    private fun SupabaseSession.toAccountState(): AccountState =
        AccountState.Authenticated(
            TsuzukiAccount(
                id = userId,
                email = email,
            ),
        )

    private companion object {
        const val ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 30L
    }
}
