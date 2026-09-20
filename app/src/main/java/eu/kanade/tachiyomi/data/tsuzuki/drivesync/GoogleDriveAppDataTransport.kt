package eu.kanade.tachiyomi.data.tsuzuki.drivesync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAuthorizedAccess
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailure
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteContent
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult
import tachiyomi.domain.tsuzuki.sync.service.DriveSyncTransport
import java.io.IOException

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class GoogleDriveAppDataTransport internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val authorizedAccess: GoogleAuthorizedAccess,
    private val metadataBaseUrl: HttpUrl,
    private val uploadBaseUrl: HttpUrl,
) : DriveSyncTransport {

    @Inject
    constructor(
        networkHelper: NetworkHelper,
        json: Json,
        authorizedAccess: GoogleAuthorizedAccess,
    ) : this(
        client = driveSafeClient(networkHelper.client),
        json = json,
        authorizedAccess = authorizedAccess,
        metadataBaseUrl = DRIVE_FILES_URL.toHttpUrl(),
        uploadBaseUrl = DRIVE_UPLOAD_FILES_URL.toHttpUrl(),
    )

    private val driveJson = Json(json) {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>> {
        val files = mutableListOf<SyncRemoteFile>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null

        do {
            val url = metadataBaseUrl.newBuilder()
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .addQueryParameter("fields", FILE_LIST_FIELDS)
                .apply {
                    pageToken?.let { addQueryParameter("pageToken", it) }
                }
                .build()

            when (val page = executeJson<DriveFileListDto>(Request.Builder().url(url).get())) {
                is DriveCallResult.Success -> {
                    val mapped = page.value.files.map { dto ->
                        dto.toRemoteFile()
                            ?: return SyncTransportResult.Failure(
                                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                            )
                    }
                    files += mapped
                    pageToken = page.value.nextPageToken?.takeIf(String::isNotBlank)
                    pageToken?.let { token ->
                        if (!seenPageTokens.add(token)) {
                            return SyncTransportResult.Failure(
                                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                            )
                        }
                    }
                }

                is DriveCallResult.Failure -> return SyncTransportResult.Failure(page.failure)
            }
        } while (pageToken != null)

        return SyncTransportResult.Success(
            files.sortedWith(compareBy(SyncRemoteFile::name, SyncRemoteFile::remoteId)),
        )
    }

    override suspend fun download(file: SyncRemoteFile): SyncTransportResult<SyncRemoteContent> {
        val url = metadataBaseUrl.newBuilder()
            .addPathSegment(file.remoteId)
            .addQueryParameter("alt", "media")
            .build()

        return when (val response = executeRaw(Request.Builder().url(url).get())) {
            is DriveCallResult.Success -> SyncTransportResult.Success(
                SyncRemoteContent(
                    file = file,
                    content = response.value,
                ),
            )

            is DriveCallResult.Failure -> SyncTransportResult.Failure(response.failure)
        }
    }

    override suspend fun generateFileId(): SyncTransportResult<String> {
        val url = metadataBaseUrl.newBuilder()
            .removePathSegment(metadataBaseUrl.pathSize - 1)
            .addPathSegment("generateIds")
            .addQueryParameter("count", "1")
            .addQueryParameter("space", APP_DATA_FOLDER)
            .build()

        return when (val response = executeJson<DriveGeneratedIdsDto>(Request.Builder().url(url).get())) {
            is DriveCallResult.Success -> {
                val id = response.value.ids.singleOrNull()?.takeIf(String::isNotBlank)
                    ?: return SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                SyncTransportResult.Success(id)
            }

            is DriveCallResult.Failure -> SyncTransportResult.Failure(response.failure)
        }
    }

    override suspend fun createReplica(
        remoteId: String,
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile> {
        if (remoteId.isBlank() || ownerDeviceId.isBlank() || documentKind == SyncDocumentKind.MANIFEST) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
            )
        }

        val url = uploadBaseUrl.newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        val metadata = driveJson.encodeToString(
            DriveCreateMetadataDto.serializer(),
            DriveCreateMetadataDto(
                id = remoteId,
                name = replicaFileName(documentKind, ownerDeviceId),
                parents = listOf(APP_DATA_FOLDER),
                mimeType = JSON_MIME_TYPE,
                appProperties = replicaAppProperties(documentKind, ownerDeviceId),
            ),
        )
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return when (
            val response = executeJson<DriveFileDto>(
                Request.Builder()
                    .url(url)
                    .post(body),
            )
        ) {
            is DriveCallResult.Success -> {
                val file = response.value.toRemoteFile()
                    ?: return SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                if (
                    file.remoteId != remoteId ||
                    file.protocolVersion != REPLICA_PROTOCOL_VERSION ||
                    file.logicalKind != documentKind ||
                    file.ownerDeviceId != ownerDeviceId
                ) {
                    SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                } else {
                    SyncTransportResult.Success(file)
                }
            }

            is DriveCallResult.Failure -> SyncTransportResult.Failure(response.failure)
        }
    }

    override suspend fun updateOwnedReplica(
        file: SyncRemoteFile,
        ownerDeviceId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile> {
        if (
            file.protocolVersion != REPLICA_PROTOCOL_VERSION ||
            file.logicalKind == null ||
            file.logicalKind == SyncDocumentKind.MANIFEST ||
            file.ownerDeviceId != ownerDeviceId
        ) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
            )
        }

        val expectedRevision = file.revision.revisionToken
            ?: return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.REMOTE_CHANGED),
            )

        val currentFile = when (val current = getMetadata(file.remoteId)) {
            is DriveCallResult.Success -> current.value.toRemoteFile()
                ?: return SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )
            is DriveCallResult.Failure -> return SyncTransportResult.Failure(current.failure)
        }
        if (
            currentFile.protocolVersion != REPLICA_PROTOCOL_VERSION ||
            currentFile.logicalKind != file.logicalKind ||
            currentFile.ownerDeviceId != ownerDeviceId
        ) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
            )
        }
        if (currentFile.revision.revisionToken != expectedRevision) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.REMOTE_CHANGED),
            )
        }

        return patchContent(file.remoteId, content)
    }

    override suspend fun getFile(remoteId: String): SyncTransportResult<SyncRemoteFile> {
        if (remoteId.isBlank()) {
            return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.MALFORMED_DOCUMENT),
            )
        }
        return when (val current = getMetadata(remoteId)) {
            is DriveCallResult.Success -> current.value.toRemoteFile()
                ?.let(SyncTransportResult::Success)
                ?: SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )

            is DriveCallResult.Failure -> SyncTransportResult.Failure(current.failure)
        }
    }

    override suspend fun create(
        documentKind: SyncDocumentKind,
        content: String,
    ): SyncTransportResult<SyncRemoteFile> {
        val url = uploadBaseUrl.newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        val metadata = driveJson.encodeToString(
            DriveCreateMetadataDto.serializer(),
            DriveCreateMetadataDto(
                name = documentKind.fileName,
                parents = listOf(APP_DATA_FOLDER),
                mimeType = JSON_MIME_TYPE,
            ),
        )
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return when (
            val response = executeJson<DriveFileDto>(
                Request.Builder()
                    .url(url)
                    .post(body),
            )
        ) {
            is DriveCallResult.Success -> response.value.toRemoteFile()
                ?.let { SyncTransportResult.Success(it) }
                ?: SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )

            is DriveCallResult.Failure -> SyncTransportResult.Failure(response.failure)
        }
    }

    override suspend fun update(
        file: SyncRemoteFile,
        content: String,
    ): SyncTransportResult<SyncRemoteFile> {
        val expectedRevision = file.revision.revisionToken
            ?: return SyncTransportResult.Failure(
                SyncFailure(SyncFailureReason.REMOTE_CHANGED),
            )

        when (val current = getMetadata(file.remoteId)) {
            is DriveCallResult.Success -> {
                val actualRevision = current.value.version
                if (actualRevision == null || actualRevision != expectedRevision) {
                    return SyncTransportResult.Failure(
                        SyncFailure(SyncFailureReason.REMOTE_CHANGED),
                    )
                }
            }

            is DriveCallResult.Failure -> return SyncTransportResult.Failure(current.failure)
        }

        return patchContent(file.remoteId, content)
    }

    private suspend fun patchContent(
        remoteId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile> {
        val url = uploadBaseUrl.newBuilder()
            .addPathSegment(remoteId)
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        return when (
            val response = executeJson<DriveFileDto>(
                Request.Builder()
                    .url(url)
                    .patch(content.toRequestBody(JSON_MEDIA_TYPE)),
            )
        ) {
            is DriveCallResult.Success -> response.value.toRemoteFile()
                ?.let { SyncTransportResult.Success(it) }
                ?: SyncTransportResult.Failure(
                    SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                )

            is DriveCallResult.Failure -> SyncTransportResult.Failure(response.failure)
        }
    }

    private suspend fun getMetadata(remoteId: String): DriveCallResult<DriveFileDto> {
        val url = metadataBaseUrl.newBuilder()
            .addPathSegment(remoteId)
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return executeJson(Request.Builder().url(url).get())
    }

    private suspend inline fun <reified T> executeJson(
        requestBuilder: Request.Builder,
    ): DriveCallResult<T> {
        return when (val response = execute(requestBuilder)) {
            is DriveHttpResult.Success -> {
                try {
                    DriveCallResult.Success(
                        driveJson.decodeFromString<T>(response.body),
                    )
                } catch (_: SerializationException) {
                    DriveCallResult.Failure(
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                } catch (_: IllegalArgumentException) {
                    DriveCallResult.Failure(
                        SyncFailure(SyncFailureReason.MALFORMED_REMOTE_DOCUMENT),
                    )
                }
            }

            is DriveHttpResult.Failure -> DriveCallResult.Failure(response.failure)
        }
    }

    private suspend fun executeRaw(
        requestBuilder: Request.Builder,
    ): DriveCallResult<String> {
        return when (val response = execute(requestBuilder)) {
            is DriveHttpResult.Success -> DriveCallResult.Success(response.body)
            is DriveHttpResult.Failure -> DriveCallResult.Failure(response.failure)
        }
    }

    private suspend fun execute(
        requestBuilder: Request.Builder,
    ): DriveHttpResult {
        val token = authorizedAccess.accessTokenOrNull()
            ?: return DriveHttpResult.Failure(
                SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED),
            )

        val request = requestBuilder
            .header("Authorization", "Bearer ${token.value}")
            .header("Accept", JSON_MIME_TYPE)
            .build()

        return try {
            client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    DriveHttpResult.Success(body)
                } else {
                    if (response.code == 401) {
                        authorizedAccess.invalidateRejectedAccessToken()
                    }
                    DriveHttpResult.Failure(
                        classifyDriveFailure(
                            response = response,
                            body = body,
                            json = driveJson,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            DriveHttpResult.Failure(
                SyncFailure(SyncFailureReason.NETWORK_UNAVAILABLE),
            )
        } catch (_: Throwable) {
            DriveHttpResult.Failure(
                SyncFailure(SyncFailureReason.UNKNOWN),
            )
        }
    }
}

internal fun driveSafeClient(client: OkHttpClient): OkHttpClient {
    return client.newBuilder()
        .apply {
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        }
        .build()
}

internal fun classifyDriveFailure(
    response: Response,
    body: String,
    json: Json,
): SyncFailure {
    val reasons = runCatching {
        json.decodeFromString<DriveErrorEnvelopeDto>(body)
            .error
            ?.errors
            .orEmpty()
            .mapNotNull { it.reason }
            .toSet()
    }.getOrDefault(emptySet())

    val retryAfterMillis = response.header("Retry-After")
        ?.toLongOrNull()
        ?.coerceAtLeast(0)
        ?.times(1_000)

    return when {
        response.code == 401 -> SyncFailure(SyncFailureReason.AUTHORIZATION_REQUIRED)
        response.code == 404 -> SyncFailure(SyncFailureReason.REMOTE_NOT_FOUND)
        response.code == 409 || response.code == 412 -> {
            SyncFailure(SyncFailureReason.REMOTE_CHANGED)
        }
        response.code == 429 || reasons.any { it in RATE_LIMIT_REASONS } -> {
            SyncFailure(
                reason = SyncFailureReason.RATE_LIMITED,
                retryAfterMillis = retryAfterMillis,
            )
        }
        response.code == 403 -> SyncFailure(SyncFailureReason.REMOTE_ACCESS_DENIED)
        response.code in 500..599 -> SyncFailure(
            reason = SyncFailureReason.REMOTE_UNAVAILABLE,
            retryAfterMillis = retryAfterMillis,
        )
        else -> SyncFailure(SyncFailureReason.UNKNOWN)
    }
}

@Serializable
internal data class DriveFileListDto(
    val nextPageToken: String? = null,
    val files: List<DriveFileDto> = emptyList(),
)

@Serializable
internal data class DriveFileDto(
    val id: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val version: String? = null,
    val appProperties: Map<String, String>? = null,
) {
    fun toRemoteFile(): SyncRemoteFile? {
        val resolvedId = id?.takeIf(String::isNotBlank) ?: return null
        val resolvedName = name?.takeIf(String::isNotBlank) ?: return null
        val revisionToken = version?.takeIf(String::isNotBlank) ?: return null

        val properties = appProperties.orEmpty()
        val protocolRaw = properties[APP_PROPERTY_PROTOCOL]
        val protocolVersion = when {
            protocolRaw == null -> null
            else -> protocolRaw.toIntOrNull() ?: return null
        }
        val logicalKindRaw = properties[APP_PROPERTY_KIND]
        val logicalKind = when {
            logicalKindRaw == null -> null
            else -> runCatching { SyncDocumentKind.valueOf(logicalKindRaw) }.getOrNull() ?: return null
        }
        val ownerDeviceId = properties[APP_PROPERTY_OWNER]?.takeIf(String::isNotBlank)

        if (
            protocolVersion == REPLICA_PROTOCOL_VERSION &&
            (logicalKind == null || logicalKind == SyncDocumentKind.MANIFEST || ownerDeviceId == null)
        ) {
            return null
        }

        return SyncRemoteFile(
            remoteId = resolvedId,
            name = resolvedName,
            mimeType = mimeType,
            revision = SyncRemoteRevision(
                remoteId = resolvedId,
                revisionToken = revisionToken,
            ),
            protocolVersion = protocolVersion,
            logicalKind = logicalKind,
            ownerDeviceId = ownerDeviceId,
        )
    }
}

@Serializable
internal data class DriveCreateMetadataDto(
    val id: String? = null,
    val name: String,
    val parents: List<String>,
    val mimeType: String,
    val appProperties: Map<String, String>? = null,
)

@Serializable
internal data class DriveGeneratedIdsDto(
    val ids: List<String> = emptyList(),
    val space: String? = null,
)

@Serializable
internal data class DriveErrorEnvelopeDto(
    val error: DriveErrorDto? = null,
)

@Serializable
internal data class DriveErrorDto(
    val errors: List<DriveErrorDetailDto> = emptyList(),
)

@Serializable
internal data class DriveErrorDetailDto(
    val reason: String? = null,
)

private sealed interface DriveHttpResult {
    data class Success(
        val body: String,
    ) : DriveHttpResult

    data class Failure(
        val failure: SyncFailure,
    ) : DriveHttpResult
}

private sealed interface DriveCallResult<out T> {
    data class Success<T>(
        val value: T,
    ) : DriveCallResult<T>

    data class Failure(
        val failure: SyncFailure,
    ) : DriveCallResult<Nothing>
}

private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_FILES_URL = "https://www.googleapis.com/upload/drive/v3/files"
private const val APP_DATA_FOLDER = "appDataFolder"
private const val PAGE_SIZE = 100
private const val JSON_MIME_TYPE = "application/json"
private const val FILE_LIST_FIELDS = "nextPageToken,files(id,name,mimeType,version,appProperties)"
private const val FILE_FIELDS = "id,name,mimeType,version,appProperties"
private const val REPLICA_PROTOCOL_VERSION = 2
private const val APP_PROPERTY_PROTOCOL = "tsuzukiProtocol"
private const val APP_PROPERTY_KIND = "logicalKind"
private const val APP_PROPERTY_OWNER = "ownerDeviceId"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val MULTIPART_RELATED = "multipart/related".toMediaType()
private fun replicaAppProperties(
    documentKind: SyncDocumentKind,
    ownerDeviceId: String,
) = mapOf(
    APP_PROPERTY_PROTOCOL to REPLICA_PROTOCOL_VERSION.toString(),
    APP_PROPERTY_KIND to documentKind.name,
    APP_PROPERTY_OWNER to ownerDeviceId,
)

private fun replicaFileName(
    documentKind: SyncDocumentKind,
    ownerDeviceId: String,
): String {
    val kind = documentKind.fileName.substringBeforeLast(".")
    val owner = ownerDeviceId.replace(Regex("[^A-Za-z0-9._-]"), "-")
    return "tsuzuki-v2-$kind-$owner.json"
}

private val RATE_LIMIT_REASONS = setOf(
    "dailyLimitExceeded",
    "rateLimitExceeded",
    "userRateLimitExceeded",
)
