package eu.kanade.tachiyomi.ui.tsuzuki.account

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.tsuzuki.supabase.AndroidKeystoreSessionStore
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAccountRepository
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAuthService
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class SupabaseAuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callback = intent?.data
        if (callback == null || !callback.isAllowedCallback()) {
            finish()
            return
        }

        val configuration = SupabaseConfiguration.fromBuildConfig()
        if (!configuration.isConfigured) {
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val store = AndroidKeystoreSessionStore(this@SupabaseAuthCallbackActivity)
                val authService = SupabaseAuthService(
                    client = OkHttpClient(),
                    configuration = configuration,
                )
                val repository = SupabaseAccountRepository(
                    authService = authService,
                    sessionStore = store,
                )
                val values = callback.callbackValues()
                val type = values["type"]
                    ?.takeIf { it in ALLOWED_TYPES }
                    ?: error("Unsupported Supabase callback type")

                val result = when {
                    values["token_hash"] != null -> {
                        authService.verifyCallbackToken(
                            tokenHash = requireNotNull(values["token_hash"]),
                            type = type,
                        )
                    }
                    values["access_token"] != null && values["refresh_token"] != null -> {
                        authService.verifyAccessToken(
                            accessToken = requireNotNull(values["access_token"]),
                            refreshToken = requireNotNull(values["refresh_token"]),
                            expiresInSeconds = values["expires_in"]?.toLongOrNull() ?: 0L,
                        )
                    }
                    else -> error("Supabase callback did not contain a supported token")
                }

                val session = requireNotNull(result.session) {
                    "Supabase callback did not create a session"
                }
                repository.acceptSession(session)
            }

            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    private fun Uri.isAllowedCallback(): Boolean =
        scheme == SupabaseConfiguration.CALLBACK_SCHEME &&
            host == SupabaseConfiguration.CALLBACK_HOST

    private fun Uri.callbackValues(): Map<String, String> {
        val values = buildMap {
            queryParameterNames.forEach { name ->
                getQueryParameter(name)?.let { put(name, it) }
            }
        }.toMutableMap()

        fragment?.takeIf(String::isNotBlank)?.let { rawFragment ->
            val fragmentUri = Uri.parse("tsuzuki://fragment?$rawFragment")
            fragmentUri.queryParameterNames.forEach { name ->
                fragmentUri.getQueryParameter(name)?.let { values[name] = it }
            }
        }
        return values
    }

    companion object {
        private val ALLOWED_TYPES = setOf("signup", "recovery", "email_change")
    }
}
