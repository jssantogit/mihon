package eu.kanade.tachiyomi.data.tsuzuki.supabase

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.SupabaseFieldPathCodec
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SupabasePushResult
import tachiyomi.domain.tsuzuki.sync.model.SupabaseRemoteConflict
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncEvent
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshot
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncSnapshotRecord
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentityClaimTransport
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncTransport

class SupabaseSyncHttpTransport(
    private val client: OkHttpClient,
    private val configuration: SupabaseConfiguration,
    private val accountRepository: AccountRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : SupabaseSyncTransport, CanonicalIdentityClaimTransport {

    override suspend fun pullDelta(
        documentKind: SyncDocumentKind,
        sinceEventId: Long,
        limit: Int,
    ): SyncTransportResult<List<SupabaseSyncEvent>> {
        require(sinceEventId >= 0) { "Supabase delta cursor must not be negative" }
        require(limit in 1..1000) { "Supabase delta limit must be between 1 and 1000" }

        val body = buildJsonObject {
            put("p_domain", JsonPrimitive(documentKind.name))
            put("p_since_event_id", JsonPrimitive(sinceEventId))
            put("p_limit", JsonPrimitive(limit))
        }
        return executeAuthenticated(
            request = rpcRequest("sync_pull_delta", body),
        ) { raw ->
            json.decodeFromString(
                JsonArray.serializer(),
                raw,
            ).map { element ->
                json.decodeFromJsonElement(SupabaseSyncEvent.serializer(), element)
            }
        }
    }

    override suspend fun snapshot(
        documentKind: SyncDocumentKind,
    ): SyncTransportResult<SupabaseSyncSnapshot> {
        val body = buildJsonObject {
            put("p_domain", JsonPrimitive(documentKind.name))
        }
        return executeAuthenticated(
            request = rpcRequest("sync_snapshot_domain", body),
        ) { raw ->
            val response = json.decodeFromString(
                SnapshotResponse.serializer(),
                raw,
            )
            SupabaseSyncSnapshot(
                documentKind = documentKind,
                cursor = response.cursor,
                records = response.records.map { record ->
                    SupabaseSyncSnapshotRecord(
                        recordId = record.recordId,
                        isDeleted = record.isDeleted,
                        fields = record.fields,
                    )
                },
            )
        }
    }

    override suspend fun push(
        batch: SupabaseMutationBatch,
    ): SyncTransportResult<SupabasePushResult> {
        val payload = buildJsonObject {
            put("mutationId", JsonPrimitive(batch.mutationId))
            put("originClientId", JsonPrimitive(batch.originClientId))
            put("domain", JsonPrimitive(batch.domain))
            put("baseCursor", JsonPrimitive(batch.baseCursor))
            put(
                "operations",
                buildJsonArray {
                    batch.operations.forEach { add(it.toBackendOperation()) }
                },
            )
        }
        val requestBody = buildJsonObject {
            put("p_request", payload)
        }

        val pushed = executeAuthenticated(
            request = rpcRequest("sync_apply_mutation_batch", requestBody),
        ) { raw ->
            raw.trim().toLong()
        }
        val cursor = when (pushed) {
            is SyncTransportResult.Failure -> return pushed
            is SyncTransportResult.Success -> pushed.value
        }

        val conflicts = getMutationConflicts(batch.mutationId)
        return when (conflicts) {
            is SyncTransportResult.Failure -> conflicts
            is SyncTransportResult.Success -> {
                SyncTransportResult.Success(
                    SupabasePushResult(
                        cursor = cursor,
                        conflicts = conflicts.value,
                    ),
                )
            }
        }
    }

    override suspend fun claim(
        provider: String,
        externalId: String,
        proposedCanonicalTitleId: String,
    ): SyncTransportResult<String> {
        require(provider.isNotBlank()) { "Identity provider must not be blank" }
        require(externalId.isNotBlank()) { "External identity must not be blank" }
        require(proposedCanonicalTitleId.isNotBlank()) {
            "Proposed canonical title ID must not be blank"
        }

        val body = buildJsonObject {
            put("p_provider", JsonPrimitive(provider))
            put("p_external_id", JsonPrimitive(externalId))
            put(
                "p_proposed_canonical_title_id",
                JsonPrimitive(proposedCanonicalTitleId),
            )
        }
        return executeAuthenticated(
            request = rpcRequest("sync_claim_external_identity", body),
        ) { raw ->
            json.decodeFromString(String.serializer(), raw)
        }
    }

    private suspend fun getMutationConflicts(
        mutationId: String,
    ): SyncTransportResult<List<SupabaseRemoteConflict>> {
        val url = configuration.endpoint("rest/v1/tsuzuki_sync_conflicts")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "select",
                "conflict_id,record_id,field_path,conflict_type,local_value,remote_value",
            )
            .addQueryParameter("local_mutation_id", "eq.$mutationId")
            .addQueryParameter("resolved_at", "is.null")
            .build()

        return executeAuthenticated(
            request = Request.Builder()
                .url(url)
                .get()
                .build(),
        ) { raw ->
            json.decodeFromString(
                ConflictResponse.serializer(),
                raw,
            ).map { row ->
                SupabaseRemoteConflict(
                    conflictId = row.conflictId,
                    recordId = row.recordId,
                    fieldPath = row.fieldPath,
                    kind = row.conflictType,
                    localValue = row.localValue,
                    remoteValue = row.remoteValue,
                )
            }
        }
    }

    private fun rpcRequest(
        functionName: String,
        body: JsonObject,
    ): Request {
        return Request.Builder()
            .url(configuration.endpoint("rest/v1/rpc/$functionName"))
            .post(
                json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
    }

    private suspend fun <T> executeAuthenticated(
        request: Request,
        decode: (String) -> T,
    ): SyncTransportResult<T> {
        if (accountRepository.state.value !is AccountState.Authenticated) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
            )
        }

        val tokenResult = accountRepository.getAccessToken()
        val accessToken = tokenResult.getOrElse { error ->
            return SyncTransportResult.Failure(error.toSyncFailure())
        }
        if (accessToken.isNullOrBlank()) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
            )
        }

        val authenticated = request.newBuilder()
            .header("apikey", configuration.publishableKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(authenticated).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) {
                    return SyncTransportResult.Failure(
                        response.code.toSyncFailure(
                            retryAfterHeader = response.header("Retry-After"),
                        ),
                    )
                }

                runCatching { decode(raw) }
                    .fold(
                        onSuccess = SyncTransportResult<T>::Success,
                        onFailure = {
                            SyncTransportResult.Failure(
                                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                            )
                        },
                    )
            }
        } catch (_: IOException) {
            SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
            )
        }
    }

    private fun SyncMutation.toBackendOperation(): JsonObject {
        return when (this) {
            is SyncMutation.SetField -> buildJsonObject {
                put("type", JsonPrimitive("set"))
                put("recordId", JsonPrimitive(recordId))
                put(
                    "fieldPath",
                    JsonPrimitive(SupabaseFieldPathCodec.encode(propertyPath)),
                )
                put("value", value)
            }
            is SyncMutation.RemoveField -> buildJsonObject {
                put("type", JsonPrimitive("remove"))
                put("recordId", JsonPrimitive(recordId))
                put(
                    "fieldPath",
                    JsonPrimitive(SupabaseFieldPathCodec.encode(propertyPath)),
                )
            }
            is SyncMutation.DeleteRecord -> buildJsonObject {
                put("type", JsonPrimitive("delete"))
                put("recordId", JsonPrimitive(recordId))
            }
        }
    }

    private fun Throwable.toSyncFailure(): SyncFailure {
        return if (this is IOException) {
            SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE)
        } else {
            SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED)
        }
    }

    private fun Int.toSyncFailure(
        retryAfterHeader: String?,
    ): SyncFailure {
        val reason = when (this) {
            400, 422 -> SyncFailureReason.MALFORMED_DOCUMENT
            401 -> SyncFailureReason.AUTHORIZATION_REQUIRED
            403 -> SyncFailureReason.REMOTE_ACCESS_DENIED
            404 -> SyncFailureReason.REMOTE_NOT_FOUND
            409 -> SyncFailureReason.REMOTE_CHANGED
            429 -> SyncFailureReason.RATE_LIMITED
            in 500..599 -> SyncFailureReason.REMOTE_UNAVAILABLE
            else -> SyncFailureReason.UNKNOWN
        }
        val retryAfterMillis = if (this == 429) {
            retryAfterHeader
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let { seconds ->
                    if (seconds > Long.MAX_VALUE / 1_000L) {
                        Long.MAX_VALUE
                    } else {
                        seconds * 1_000L
                    }
                }
        } else {
            null
        }
        return SyncFailure(
            reason = reason,
            retryAfterMillis = retryAfterMillis,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class SnapshotResponse(
    val cursor: Long,
    val records: List<SnapshotRecordResponse>,
)

@Serializable
private data class SnapshotRecordResponse(
    val recordId: String,
    val isDeleted: Boolean,
    val fields: JsonObject,
)

@Serializable
private data class ConflictRow(
    @SerialName("conflict_id")
    val conflictId: Long,
    @SerialName("record_id")
    val recordId: String,
    @SerialName("field_path")
    val fieldPath: String? = null,
    @SerialName("conflict_type")
    val conflictType: SyncConflictKind,
    @SerialName("local_value")
    val localValue: JsonElement? = null,
    @SerialName("remote_value")
    val remoteValue: JsonElement? = null,
)

private object ConflictResponse {
    val serializer = kotlinx.serialization.builtins.ListSerializer(ConflictRow.serializer())
}
