package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SyncConflictKind
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.model.SyncMutation
import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

class SupabaseSyncHttpTransportTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `logged out transport returns authorization required without request`() = runTest {
        val transport = transport(
            account = FakeAccountRepository(AccountState.LoggedOut, token = null),
        )

        val result = transport.pullDelta(
            documentKind = SyncDocumentKind.LIBRARY,
            sinceEventId = 0,
        )

        (result as SyncTransportResult.Failure).failure.reason shouldBe
            SyncFailureReason.AUTHORIZATION_REQUIRED
        server.requestCount shouldBe 0
    }

    @Test
    fun `push converts local mutation paths and returns mutation scoped conflicts`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("12")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    [
                      {
                        "conflict_id":99,
                        "record_id":"title-1",
                        "field_path":"progress/chapter~1number",
                        "conflict_type":"FIELD_DIVERGENCE",
                        "local_value":{"removed":false,"value":3},
                        "remote_value":{"removed":false,"value":2}
                      }
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val transport = transport()
        val batch = SupabaseMutationBatch(
            mutationId = "10000000-0000-0000-0000-000000000001",
            originClientId = "device-1",
            domain = SyncDocumentKind.LIBRARY.name,
            baseCursor = 7,
            operations = listOf(
                SyncMutation.SetField(
                    recordId = "title-1",
                    propertyPath = listOf("progress", "chapter/number"),
                    value = JsonPrimitive(3),
                    recordUpdatedAtEpochMillis = 10,
                ),
            ),
        )

        val result = transport.push(batch)

        val success = result as SyncTransportResult.Success
        success.value.cursor shouldBe 12
        success.value.conflicts.shouldHaveSize(1)
        success.value.conflicts.single().kind shouldBe SyncConflictKind.FIELD_DIVERGENCE

        val rpc = server.takeRequest()
        rpc.url.encodedPath shouldBe "/rest/v1/rpc/sync_apply_mutation_batch"
        rpc.headers["apikey"] shouldBe "publishable-test-key"
        rpc.headers["Authorization"] shouldBe "Bearer access-token"
        val body = rpc.body!!.utf8()
        body.contains("\"mutationId\":\"10000000-0000-0000-0000-000000000001\"") shouldBe true
        body.contains("\"fieldPath\":\"progress/chapter~1number\"") shouldBe true
        body.contains("\"recordUpdatedAtEpochMillis\"") shouldBe false

        val conflicts = server.takeRequest()
        conflicts.url.encodedPath shouldBe "/rest/v1/tsuzuki_sync_conflicts"
        conflicts.url.queryParameter("local_mutation_id") shouldBe
            "eq.10000000-0000-0000-0000-000000000001"
        conflicts.url.queryParameter("resolved_at") shouldBe "is.null"
    }

    @Test
    fun `pull delta sends accepted cursor and decodes ordered events`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    [
                      {
                        "eventId":8,
                        "domain":"LIBRARY",
                        "recordId":"title-1",
                        "fieldPath":"status",
                        "operation":"set",
                        "value":"READING",
                        "originClientId":"device-2",
                        "mutationId":"10000000-0000-0000-0000-000000000002",
                        "createdAt":"2026-09-21T12:00:00Z"
                      }
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val transport = transport()

        val result = transport.pullDelta(
            documentKind = SyncDocumentKind.LIBRARY,
            sinceEventId = 7,
            limit = 50,
        )

        val success = result as SyncTransportResult.Success
        success.value.single().eventId shouldBe 8
        success.value.single().value shouldBe JsonPrimitive("READING")

        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_pull_delta"
        val body = request.body!!.utf8()
        body.contains("\"p_domain\":\"LIBRARY\"") shouldBe true
        body.contains("\"p_since_event_id\":7") shouldBe true
        body.contains("\"p_limit\":50") shouldBe true
    }

    @Test
    fun `claim external identity uses authenticated rpc and returns claimed id`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("\"canon-cloud\"")
                .build(),
        )
        val transport = transport()

        val result = transport.claim(
            provider = "kitsu",
            externalId = "1",
            proposedCanonicalTitleId = "canon-local",
        )

        (result as SyncTransportResult.Success).value shouldBe "canon-cloud"
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/rest/v1/rpc/sync_claim_external_identity"
        request.body!!.utf8().contains("\"p_proposed_canonical_title_id\":\"canon-local\"") shouldBe true
    }

    private fun transport(
        account: AccountRepository = FakeAccountRepository(
            AccountState.Authenticated(
                TsuzukiAccount("user-1", "reader@example.com"),
            ),
            token = "access-token",
        ),
    ) = SupabaseSyncHttpTransport(
        client = OkHttpClient(),
        configuration = SupabaseConfiguration(
            url = server.url("/").toString().removeSuffix("/"),
            publishableKey = "publishable-test-key",
        ),
        accountRepository = account,
    )

    private class FakeAccountRepository(
        initial: AccountState,
        private val token: String?,
    ) : AccountRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AccountState> = mutableState

        override suspend fun register(email: String, password: String) = error("not used")

        override suspend fun login(email: String, password: String) = error("not used")

        override suspend fun logout() = error("not used")

        override suspend fun sendPasswordRecovery(email: String) = error("not used")

        override suspend fun refreshSession() = error("not used")

        override suspend fun getAccessToken() = Result.success(token)
    }
}
