package eu.kanade.tachiyomi.data.tsuzuki.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount

class SupabaseAuthService(
    private val client: OkHttpClient,
    private val configuration: SupabaseConfiguration,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {

    suspend fun register(email: String, password: String): AuthResult =
        post(
            path = "auth/v1/signup",
            body = CredentialsRequest(email = email, password = password),
        )

    suspend fun login(email: String, password: String): AuthResult =
        post(
            path = "auth/v1/token?grant_type=password",
            body = CredentialsRequest(email = email, password = password),
        )

    suspend fun refresh(refreshToken: String): AuthResult =
        post(
            path = "auth/v1/token?grant_type=refresh_token",
            body = RefreshRequest(refreshToken),
        )

    suspend fun sendPasswordRecovery(email: String) {
        postRaw(
            path = "auth/v1/recover",
            body = json.encodeToString(RecoveryRequest.serializer(), RecoveryRequest(email)),
        )
    }

    suspend fun logout(accessToken: String) {
        request(
            Request.Builder()
                .url(configuration.endpoint("auth/v1/logout"))
                .post(EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE))
                .header("apikey", configuration.publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .build(),
        ).close()
    }

    suspend fun verifyCallbackToken(tokenHash: String, type: String): AuthResult =
        post(
            path = "auth/v1/verify",
            body = VerifyRequest(
                tokenHash = tokenHash,
                type = type,
            ),
        )

    suspend fun verifyAccessToken(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
    ): AuthResult {
        val response = request(
            Request.Builder()
                .url(configuration.endpoint("auth/v1/user"))
                .get()
                .header("apikey", configuration.publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .build(),
        )
        response.use {
            val raw = it.body.string()
            check(it.isSuccessful) { "Supabase auth request failed with HTTP ${it.code}" }
            val user = json.decodeFromString(UserResponse.serializer(), raw)
            val email = requireNotNull(user.email) { "Supabase user response did not contain an email" }
            return AuthResult(
                account = TsuzukiAccount(id = user.id, email = email),
                session = SupabaseSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtEpochSeconds = nowEpochSeconds() + expiresInSeconds,
                    userId = user.id,
                    email = email,
                ),
            )
        }
    }

    private suspend inline fun <reified T> post(path: String, body: T): AuthResult {
        val serializer = kotlinx.serialization.serializer<T>()
        val raw = json.encodeToString(serializer, body)
        val response = postRaw(path, raw)
        response.use {
            val responseBody = it.body.string()
            check(it.isSuccessful) { "Supabase auth request failed with HTTP ${it.code}" }
            return decodeAuthResult(responseBody)
        }
    }

    private suspend fun postRaw(path: String, body: String) =
        request(
            Request.Builder()
                .url(configuration.endpoint(path))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("apikey", configuration.publishableKey)
                .header("Authorization", "Bearer ${configuration.publishableKey}")
                .build(),
        )

    private fun request(request: Request) = client.newCall(request).execute()

    private fun decodeAuthResult(raw: String): AuthResult {
        val response = json.decodeFromString(AuthResponse.serializer(), raw)
        val user = response.user
        val account = user?.let {
            val email = requireNotNull(it.email) { "Supabase user response did not contain an email" }
            TsuzukiAccount(id = it.id, email = email)
        }
        val accessToken = response.accessToken
        val refreshToken = response.refreshToken
        val session = if (accessToken != null && refreshToken != null && account != null) {
            SupabaseSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSeconds = nowEpochSeconds() + (response.expiresIn ?: 0L),
                userId = account.id,
                email = account.email,
            )
        } else {
            null
        }
        return AuthResult(account = account, session = session)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val EMPTY_JSON = "{}"
    }
}

data class AuthResult(
    val account: TsuzukiAccount?,
    val session: SupabaseSession?,
)

@Serializable
data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String,
)

@Serializable
private data class CredentialsRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token")
    val refreshToken: String,
)

@Serializable
private data class RecoveryRequest(
    val email: String,
)

@Serializable
private data class VerifyRequest(
    @SerialName("token_hash")
    val tokenHash: String,
    val type: String,
)

@Serializable
private data class AuthResponse(
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    val user: UserResponse? = null,
)

@Serializable
private data class UserResponse(
    val id: String,
    val email: String? = null,
)
