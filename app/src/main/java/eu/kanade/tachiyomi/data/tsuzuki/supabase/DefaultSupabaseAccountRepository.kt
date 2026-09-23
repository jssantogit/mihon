package eu.kanade.tachiyomi.data.tsuzuki.supabase

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSupabaseAccountRepository(
    context: Context,
    networkHelper: NetworkHelper,
    json: Json,
) : AccountRepository {

    private val delegate = SupabaseAccountRepository(
        authService = SupabaseAuthService(
            client = supabaseSafeClient(networkHelper.client),
            configuration = SupabaseConfiguration.fromBuildConfig(),
            json = json,
        ),
        sessionStore = AndroidKeystoreSessionStore(context, json),
    )

    override val state: StateFlow<AccountState>
        get() = delegate.state

    override suspend fun register(email: String, password: String) =
        delegate.register(email, password)

    override suspend fun login(email: String, password: String) =
        delegate.login(email, password)

    override suspend fun logout() = delegate.logout()

    override suspend fun sendPasswordRecovery(email: String) =
        delegate.sendPasswordRecovery(email)

    override suspend fun refreshSession() = delegate.refreshSession()

    override suspend fun getAccessToken() = delegate.getAccessToken()
}
