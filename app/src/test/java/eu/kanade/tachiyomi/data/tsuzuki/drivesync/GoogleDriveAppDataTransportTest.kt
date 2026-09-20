package eu.kanade.tachiyomi.data.tsuzuki.drivesync

import eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAccessToken
import eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAuthorizationOperationResult
import eu.kanade.tachiyomi.data.tsuzuki.googleauth.GoogleAuthorizedAccess
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteFile
import tachiyomi.domain.tsuzuki.sync.model.SyncRemoteRevision
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

class GoogleDriveAppDataTransportTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `case 1 - list is restricted to appDataFolder and paginates`() = kotlinx.coroutines.test.runTest {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request, index ->
            requests += request
            when (index) {
                0 -> response(
                    request,
                    200,
                    """{"nextPageToken":"page-2","files":[{"id":"1","name":"library.json","mimeType":"application/json","version":"7"}]}""",
                )
                else -> response(
                    request,
                    200,
                    """{"files":[{"id":"2","name":"settings.json","mimeType":"application/json","version":"3"}]}""",
                )
            }
        }
        val access = FakeAuthorizedAccess()

        val result = transport(client, access).listFiles() as SyncTransportResult.Success

        result.value.map { it.name } shouldContainExactly listOf("library.json", "settings.json")
        requests.size shouldBe 2
        requests[0].url.queryParameter("spaces") shouldBe "appDataFolder"
        requests[0].url.queryParameter("pageToken") shouldBe null
        requests[1].url.queryParameter("pageToken") shouldBe "page-2"
        requests.all { it.header("Authorization") == "Bearer test-token" } shouldBe true
    }

    @Test
    fun `case 2 - create targets appDataFolder with multipart metadata and content`() =
        kotlinx.coroutines.test.runTest {
            lateinit var captured: Request
            val client = fakeClient { request, _ ->
                captured = request
                response(
                    request,
                    200,
                    """{"id":"new-id","name":"library.json","mimeType":"application/json","version":"1"}""",
                )
            }

            val result = transport(client).create(
                documentKind = SyncDocumentKind.LIBRARY,
                content = """{"schemaVersion":1}""",
            ) as SyncTransportResult.Success

            result.value.remoteId shouldBe "new-id"
            captured.method shouldBe "POST"
            captured.url.queryParameter("uploadType") shouldBe "multipart"
            captured.body!!.contentType().toString() shouldContain "multipart/related"
            val buffered = okio.Buffer()
            captured.body!!.writeTo(buffered)
            val body = buffered.readUtf8()
            body shouldContain """"parents":["appDataFolder"]"""
            body shouldContain """"name":"library.json""""
            body shouldContain """"schemaVersion":1"""
            body shouldNotContain "test-token"
        }

    @Test
    fun `case 3 - update refuses to overwrite when remote version changed`() =
        kotlinx.coroutines.test.runTest {
            val requests = mutableListOf<Request>()
            val client = fakeClient { request, _ ->
                requests += request
                response(
                    request,
                    200,
                    """{"id":"1","name":"library.json","mimeType":"application/json","version":"8"}""",
                )
            }

            val result = transport(client).update(
                file = remoteFile(version = "7"),
                content = "{}",
            ) as SyncTransportResult.Failure

            result.failure.reason shouldBe SyncFailureReason.REMOTE_CHANGED
            requests.size shouldBe 1
            requests.single().method shouldBe "GET"
        }

    @Test
    fun `case 4 - successful update preflights version then patches media`() =
        kotlinx.coroutines.test.runTest {
            val requests = mutableListOf<Request>()
            val client = fakeClient { request, index ->
                requests += request
                if (index == 0) {
                    response(
                        request,
                        200,
                        """{"id":"1","name":"library.json","mimeType":"application/json","version":"7"}""",
                    )
                } else {
                    response(
                        request,
                        200,
                        """{"id":"1","name":"library.json","mimeType":"application/json","version":"8"}""",
                    )
                }
            }

            val result = transport(client).update(
                file = remoteFile(version = "7"),
                content = """{"updated":true}""",
            ) as SyncTransportResult.Success

            result.value.revision.revisionToken shouldBe "8"
            requests.map { it.method } shouldContainExactly listOf("GET", "PATCH")
            requests[1].url.queryParameter("uploadType") shouldBe "media"
        }


    @Test
    fun `v2 list exposes private replica metadata`() = kotlinx.coroutines.test.runTest {
        val client = fakeClient { request, _ ->
            response(
                request,
                200,
                """{"files":[{"id":"1","name":"diag.json","mimeType":"application/json","version":"7","appProperties":{"tsuzukiProtocol":"2","logicalKind":"LIBRARY","ownerDeviceId":"device:A"}}]}""",
            )
        }

        val result = transport(client).listFiles() as SyncTransportResult.Success
        val file = result.value.single()

        file.protocolVersion shouldBe 2
        file.logicalKind shouldBe SyncDocumentKind.LIBRARY
        file.ownerDeviceId shouldBe "device:A"
    }

    @Test
    fun `generate id targets appDataFolder and returns exactly one id`() = kotlinx.coroutines.test.runTest {
        lateinit var captured: Request
        val client = fakeClient { request, _ ->
            captured = request
            response(request, 200, """{"ids":["reserved-1"],"space":"appDataFolder"}""")
        }

        val result = transport(client).generateFileId() as SyncTransportResult.Success

        result.value shouldBe "reserved-1"
        captured.method shouldBe "GET"
        captured.url.encodedPath shouldContain "generateIds"
        captured.url.queryParameter("count") shouldBe "1"
        captured.url.queryParameter("space") shouldBe "appDataFolder"
    }

    @Test
    fun `create replica uses reserved id and appProperties`() = kotlinx.coroutines.test.runTest {
        lateinit var captured: Request
        val client = fakeClient { request, _ ->
            captured = request
            response(
                request,
                200,
                """{"id":"reserved-1","name":"tsuzuki-v2-library-device-A.json","mimeType":"application/json","version":"1","appProperties":{"tsuzukiProtocol":"2","logicalKind":"LIBRARY","ownerDeviceId":"device:A"}}""",
            )
        }

        val result = transport(client).createReplica(
            remoteId = "reserved-1",
            documentKind = SyncDocumentKind.LIBRARY,
            ownerDeviceId = "device:A",
            content = """{"protocolVersion":2}""",
        ) as SyncTransportResult.Success

        result.value.remoteId shouldBe "reserved-1"
        val buffer = okio.Buffer()
        captured.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        body shouldContain """"id":"reserved-1""""
        body shouldContain """"tsuzukiProtocol":"2""""
        body shouldContain """"logicalKind":"LIBRARY""""
        body shouldContain """"ownerDeviceId":"device:A""""
        body shouldContain """"parents":["appDataFolder"]"""
        body shouldNotContain "test-token"
    }

    @Test
    fun `owned update refuses foreign replica before network request`() = kotlinx.coroutines.test.runTest {
        var requested = false
        val client = fakeClient { request, _ ->
            requested = true
            response(request, 500, "{}")
        }

        val result = transport(client).updateOwnedReplica(
            file = remoteFile(
                version = "7",
                protocolVersion = 2,
                logicalKind = SyncDocumentKind.LIBRARY,
                ownerDeviceId = "device:B",
            ),
            ownerDeviceId = "device:A",
            content = "{}",
        ) as SyncTransportResult.Failure

        result.failure.reason shouldBe SyncFailureReason.MALFORMED_REMOTE_DOCUMENT
        requested.shouldBeFalse()
    }

    @Test
    fun `case 5 - 401 invalidates transient Google access and requires reauthorization`() =
        kotlinx.coroutines.test.runTest {
            val access = FakeAuthorizedAccess()
            val client = fakeClient { request, _ ->
                response(
                    request,
                    401,
                    """{"error":{"errors":[{"reason":"authError"}],"code":401}}""",
                )
            }

            val result = transport(client, access).listFiles() as SyncTransportResult.Failure

            result.failure.reason shouldBe SyncFailureReason.AUTHORIZATION_REQUIRED
            access.invalidations shouldBe 1
        }

    @Test
    fun `case 6 - quota 403 does not invalidate Google authorization`() =
        kotlinx.coroutines.test.runTest {
            val access = FakeAuthorizedAccess()
            val client = fakeClient { request, _ ->
                response(
                    request,
                    403,
                    """{"error":{"errors":[{"reason":"userRateLimitExceeded"}],"code":403}}""",
                )
            }

            val result = transport(client, access).listFiles() as SyncTransportResult.Failure

            result.failure.reason shouldBe SyncFailureReason.RATE_LIMITED
            access.invalidations shouldBe 0
        }

    @Test
    fun `case 7 - ordinary 403 remains access denied and does not sign user out`() =
        kotlinx.coroutines.test.runTest {
            val access = FakeAuthorizedAccess()
            val client = fakeClient { request, _ ->
                response(
                    request,
                    403,
                    """{"error":{"errors":[{"reason":"domainPolicy"}],"code":403}}""",
                )
            }

            val result = transport(client, access).listFiles() as SyncTransportResult.Failure

            result.failure.reason shouldBe SyncFailureReason.REMOTE_ACCESS_DENIED
            access.invalidations shouldBe 0
        }

    @Test
    fun `case 8 - missing token fails before any network request`() =
        kotlinx.coroutines.test.runTest {
            var requested = false
            val client = fakeClient { request, _ ->
                requested = true
                response(request, 500, "{}")
            }
            val access = FakeAuthorizedAccess(token = null)

            val result = transport(client, access).listFiles() as SyncTransportResult.Failure

            result.failure.reason shouldBe SyncFailureReason.AUTHORIZATION_REQUIRED
            requested.shouldBeFalse()
        }

    @Test
    fun `case 9 - Drive client strips HTTP logging interceptors`() {
        val logger = HttpLoggingInterceptor()
        val base = OkHttpClient.Builder()
            .addInterceptor(logger)
            .addNetworkInterceptor(logger)
            .build()

        val safe = driveSafeClient(base)

        safe.interceptors.any { it is HttpLoggingInterceptor }.shouldBeFalse()
        safe.networkInterceptors.any { it is HttpLoggingInterceptor }.shouldBeFalse()
    }

    private fun transport(
        client: OkHttpClient,
        access: FakeAuthorizedAccess = FakeAuthorizedAccess(),
    ) = GoogleDriveAppDataTransport(
        client = client,
        json = json,
        authorizedAccess = access,
        metadataBaseUrl = "https://example.test/drive/v3/files".toHttpUrl(),
        uploadBaseUrl = "https://example.test/upload/drive/v3/files".toHttpUrl(),
    )

    private fun remoteFile(
        version: String,
        protocolVersion: Int? = null,
        logicalKind: SyncDocumentKind? = null,
        ownerDeviceId: String? = null,
    ) = SyncRemoteFile(
        remoteId = "1",
        name = if (protocolVersion == 2) "tsuzuki-v2-library-device.json" else "library.json",
        mimeType = "application/json",
        revision = SyncRemoteRevision(
            remoteId = "1",
            revisionToken = version,
        ),
        protocolVersion = protocolVersion,
        logicalKind = logicalKind,
        ownerDeviceId = ownerDeviceId,
    )

    private fun fakeClient(
        handler: (Request, Int) -> Response,
    ): OkHttpClient {
        var requestIndex = 0
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    handler(chain.request(), requestIndex++)
                },
            )
            .build()
    }

    private fun response(
        request: Request,
        code: Int,
        body: String,
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private class FakeAuthorizedAccess(
        private val token: GoogleAccessToken? = GoogleAccessToken("test-token"),
    ) : GoogleAuthorizedAccess {
        var invalidations = 0

        override fun accessTokenOrNull(): GoogleAccessToken? = token

        override suspend fun invalidateRejectedAccessToken(): GoogleAuthorizationOperationResult {
            invalidations += 1
            return GoogleAuthorizationOperationResult.Success
        }
    }
}
