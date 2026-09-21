# Tsuzuki Modular Runtime — Dev C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Google/Drive with optional Supabase account + local-first sync, make Home/Collections empty-by-default and capability-driven, and perform the final Home/Search/Library/Settings shell integration after Dev A and Dev B are merged.

**Architecture:** Local SQLDelight remains authoritative. Dev C adds a thin Supabase HTTP/Auth/PostgREST boundary using the existing OkHttp/serialization stack, a transactional mutation/delta protocol in Postgres with RLS and idempotency, then reuses existing Tsuzuki sync adapters/diff machinery where possible. Dev C also owns the shared UI shell so Dev A/B never fight over navigation or Settings main files.

**Tech Stack:** Kotlin, OkHttp 5, kotlinx.serialization, SQLDelight, PostgreSQL/Supabase Auth/PostgREST RPC, Jetpack Compose, GitHub Actions CI v2, Supabase CLI for backend tests.

**Spec:** `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`

## Global Constraints

- Branch only after Wave 0 foundation is merged.
- Supabase account is optional; app launch/local Library/Reader/downloads/Add-ons/Collections never require login.
- Only e-mail/password Supabase registration/login is implemented.
- Google login is removed from target product.
- No Google Drive import or dual-write path exists.
- Local user actions commit before remote sync.
- Retry after timeout must be idempotent.
- No blind last-write-wins for conflicting concurrent field edits.
- RLS must prevent cross-user access.
- Service-role keys never ship in app code or CI artifacts.
- Synced Add-on state cannot install/trust executable code.
- Home discovery is empty by default; Continue Reading is automatic only after real progress.
- Dev C owns `HomeScreen.kt`, `SettingsMainScreen.kt`, Gradle dependency/config changes, Google/Drive deletion, and final navigation wiring.
- Dev C must not implement search identity, content resolution, or Reader selection.
- Fast CI per app task; backend/RLS changes also run Supabase database tests; final shell uses `[ci-full]` and `[apk]`.

## Review Focus

- Logged-out state must remain fully usable locally and must not discard pending outbox work.
- Same mutation replayed after unknown network outcome must apply once.
- Two devices editing different fields must merge; same-field divergence must surface an explicit conflict.
- RLS must reject another authenticated user’s rows even through RPC functions.
- Final navigation must not retain hidden product dependencies on Updates/History/Browse/Google Drive.

---

### Task C1: Add Supabase configuration and optional account domain

**Files:**
- Modify: `gradle/libs.versions.toml` only if a new helper dependency is actually required; prefer existing OkHttp/serialization so no Supabase SDK dependency is necessary.
- Modify: `app/build.gradle.kts`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/account/model/TsuzukiAccount.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/account/model/AccountState.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/account/repository/AccountRepository.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SupabaseConfiguration.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SupabaseAuthService.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SupabaseAccountRepository.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/AndroidKeystoreSessionStore.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/account/SupabaseAuthCallbackActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SupabaseAccountRepositoryTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/AndroidKeystoreSessionStoreTest.kt`

**Interfaces:**
- Produces: `AccountRepository.state: StateFlow<AccountState>`, register/login/logout/refresh/recovery.
- Consumes: Supabase project URL + publishable key injected through BuildConfig/Gradle properties.

- [ ] **Step 1: Write logged-out-local behavior tests**

~~~kotlin
sealed interface AccountState {
    data object LoggedOut : AccountState
    data class Authenticated(val account: TsuzukiAccount) : AccountState
    data class EmailConfirmationRequired(val email: String) : AccountState
}

interface AccountRepository {
    val state: StateFlow<AccountState>
    suspend fun register(email: String, password: String): Result<AccountState>
    suspend fun login(email: String, password: String): Result<AccountState>
    suspend fun logout(): Result<Unit>
    suspend fun sendPasswordRecovery(email: String): Result<Unit>
    suspend fun refreshSession(): Result<AccountState>
}
~~~

Test that constructing the repository without a stored session starts `LoggedOut` and performs no network request.

- [ ] **Step 2: Write authentication request tests with MockWebServer/fake OkHttp chain**

Verify:
- signup uses `/auth/v1/signup`;
- password sign-in uses `/auth/v1/token?grant_type=password`;
- refresh uses `grant_type=refresh_token`;
- recovery uses `/auth/v1/recover`;
- all requests carry only the publishable key and never a service-role key.

- [ ] **Step 3: Implement build-time configuration**

Expose:

~~~kotlin
data class SupabaseConfiguration(
    val url: String,
    val publishableKey: String,
)
~~~

Use Gradle properties such as `TSUZUKI_SUPABASE_URL` and `TSUZUKI_SUPABASE_PUBLISHABLE_KEY` to populate BuildConfig. The app must compile with blank values; blank config means cloud/account UI reports “not configured” in development rather than blocking local app use.

- [ ] **Step 4: Implement Keystore-backed session persistence**

Store access/refresh session JSON encrypted using an AES/GCM key generated in Android Keystore. The encryption key is non-exportable; plaintext tokens never enter generic Preferences or sync documents.

- [ ] **Step 5: Implement confirmation/recovery callback**

Configure a Tsuzuki app deep link for Supabase Auth confirmation/recovery redirects. The callback activity accepts only the configured scheme/host, validates the callback type, exchanges/verifies the Supabase token/code through `SupabaseAuthService`, persists the resulting session, and immediately finishes back into Settings.

Do not implement any Google/OAuth provider route.

- [ ] **Step 6: Implement repository/session refresh**

On 401 from later Supabase requests, refresh once using the stored refresh token, persist the replacement session, then retry only the safe request.

Logout clears local session but does not delete local Tsuzuki user data.

- [ ] **Step 8: Run tests and commit**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*SupabaseAccountRepositoryTest' \
  :app:testDebugUnitTest --tests '*AndroidKeystoreSessionStoreTest' \
  :app:compileDebugKotlin \
  spotlessCheck
git add app/build.gradle.kts gradle/libs.versions.toml \
  domain/src/main/java/tachiyomi/domain/tsuzuki/account \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase \
  app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/account/SupabaseAuthCallbackActivity.kt \
  app/src/main/AndroidManifest.xml \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/supabase
git commit -m "feat(tsuzuki): add optional Supabase account"
~~~

---

### Task C2: Create Supabase sync schema, RLS, idempotent RPC, and delta API

**Files:**
- Create: `supabase/config.toml`
- Create: `supabase/migrations/20260920000100_tsuzuki_sync.sql`
- Create: `supabase/tests/tsuzuki_sync_rls.sql`
- Create: `supabase/tests/tsuzuki_sync_conflicts.sql`
- Modify: `.github/workflows/ci-v2.yml`

**Interfaces:**
- Produces RPCs: `sync_apply_mutation_batch`, `sync_pull_delta`, `sync_snapshot_domain`, `sync_get_cursor`.
- Consumes authenticated Supabase `auth.uid()`.

- [ ] **Step 1: Write the backend schema in one migration**

Use normalized per-field state so concurrency can be checked against a client base cursor.

Core tables:

~~~sql
create table public.tsuzuki_sync_fields (
  user_id uuid not null references auth.users(id) on delete cascade,
  domain text not null,
  record_id text not null,
  field_path text not null,
  value jsonb,
  is_removed boolean not null default false,
  last_event_id bigint not null default 0,
  primary key (user_id, domain, record_id, field_path)
);

create table public.tsuzuki_sync_records (
  user_id uuid not null references auth.users(id) on delete cascade,
  domain text not null,
  record_id text not null,
  is_deleted boolean not null default false,
  delete_last_event_id bigint not null default 0,
  primary key (user_id, domain, record_id)
);

create table public.tsuzuki_sync_events (
  event_id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  domain text not null,
  record_id text not null,
  field_path text,
  operation text not null check (operation in ('set','remove','delete')),
  value jsonb,
  origin_client_id text not null,
  mutation_id uuid not null,
  created_at timestamptz not null default now()
);

create index tsuzuki_sync_events_user_domain_cursor
on public.tsuzuki_sync_events(user_id, domain, event_id);

create table public.tsuzuki_sync_mutations (
  user_id uuid not null references auth.users(id) on delete cascade,
  mutation_id uuid not null,
  request_hash text not null,
  result_cursor bigint not null,
  created_at timestamptz not null default now(),
  primary key (user_id, mutation_id)
);

create table public.tsuzuki_canonical_identity_claims (
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null,
  external_id text not null,
  canonical_title_id text not null,
  claimed_at timestamptz not null default now(),
  primary key (user_id, provider, external_id)
);

create table public.tsuzuki_sync_conflicts (
  conflict_id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  domain text not null,
  record_id text not null,
  field_path text,
  conflict_type text not null,
  local_value jsonb,
  remote_value jsonb,
  local_mutation_id uuid not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);
~~~

- [ ] **Step 2: Enable RLS and write owner-only policies**

For every table:

~~~sql
alter table public.tsuzuki_sync_fields enable row level security;
create policy "users own sync fields"
on public.tsuzuki_sync_fields
for all
using (user_id = auth.uid())
with check (user_id = auth.uid());
~~~

Repeat for records/events/mutations/conflicts/identity claims.

RPCs use `security invoker` where practical. Any `security definer` helper must explicitly compare its user scope to `auth.uid()` and use a locked `search_path`.

- [ ] **Step 3: Implement canonical external-identity claim RPC**

Create:

~~~sql
sync_claim_external_identity(
  p_provider text,
  p_external_id text,
  p_proposed_canonical_title_id text
) returns text
~~~

Inside one transaction:
1. scope all work to `auth.uid()`;
2. attempt to insert `(user_id, provider, external_id, proposed_canonical_title_id)`;
3. on uniqueness conflict, return the already-claimed `canonical_title_id`;
4. never compare display title and never let request order alter an existing claim;
5. same verified `provider + external_id` therefore converges to one cloud canonical ID.

Cross-provider equivalence is not inferred here. A second provider is attached to the same canonical ID only after Dev A’s verified identity logic has already established the equivalence.

- [ ] **Step 4: Implement `sync_apply_mutation_batch` transaction semantics**

Input JSON contains:

~~~json
{
  "mutationId": "uuid",
  "originClientId": "device-id",
  "domain": "LIBRARY",
  "baseCursor": 42,
  "operations": [
    {"type":"set","recordId":"title-1","fieldPath":"status","value":"READING"}
  ]
}
~~~

Rules:
1. compute stable request hash inside SQL;
2. insert `(auth.uid(), mutation_id, request_hash)` atomically;
3. if same mutation exists with same hash, return stored cursor;
4. if same mutation ID exists with another hash, raise a validation error;
5. for each field op, compare its `last_event_id` to `baseCursor`;
6. if later remote event exists with equivalent requested value/removal, collapse;
7. if later remote event differs, insert FIELD_DIVERGENCE and do not overwrite that field;
8. independent fields apply and emit events;
9. delete concurrent with later field edit emits DELETE_EDIT;
10. return highest emitted/known event cursor.

- [ ] **Step 5: Implement delta/snapshot RPCs**

`sync_pull_delta(p_domain, p_since_event_id, p_limit)` returns only caller-owned events ordered ascending.

`sync_get_cursor(p_domain)` returns max event ID for caller/domain, or 0.

`sync_snapshot_domain(p_domain)` returns materialized records plus a snapshot cursor captured consistently enough that subsequent delta pull from that cursor cannot miss committed events.

- [ ] **Step 6: Write RLS and concurrency tests**

`supabase/tests/tsuzuki_sync_rls.sql` proves user A cannot select/update user B state.

`tsuzuki_sync_conflicts.sql` proves:
- same mutation replay = one effect;
- same mutation ID/different request = error;
- independent fields merge;
- same-field concurrent different value produces conflict;
- concurrent delete/edit produces conflict;
- two proposed canonical IDs for the same verified external identity receive the same claimed canonical ID;
- identity claims for equal display titles but different external identities remain separate.

- [ ] **Step 7: Add backend CI**

Extend CI v2 with a database-backend job triggered when `supabase/**` changes. Use the Supabase CLI to start the local stack and execute:

~~~bash
supabase db reset
supabase test db
~~~

The job must run without production credentials.

- [ ] **Step 8: Commit**

~~~bash
git add supabase .github/workflows/ci-v2.yml
git commit -m "feat(tsuzuki): add transactional Supabase sync backend"
~~~

Push and require both normal CI v2 and Supabase database tests green.

---

### Task C3: Replace Drive transport with Supabase mutation/delta client

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SupabaseMutationBatch.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SupabaseSyncTransport.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SupabaseSyncOrchestrator.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/CanonicalIdentityClaimTransport.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/sync/SupabaseSyncStateRepository.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SupabaseSyncHttpTransport.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SyncClientIdentityStore.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/SupabaseSyncOrchestratorTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/supabase/SupabaseSyncHttpTransportTest.kt`

**Interfaces:**
- Consumes: existing `SyncMutation`, `SyncDocumentDiffer`, `SyncDocumentAdapter`, `SyncOutboxRepository`; Wave 0 Supabase cursor/pending-mutation tables; AccountRepository; Dev A `MergeCanonicalTitles`.
- Produces: pull-first local-first sync without Drive files plus cloud reconciliation of verified external identity claims.

- [ ] **Step 1: Write idempotent ambiguous-push test**

~~~kotlin
@Test
fun `timeout after server apply reuses persisted mutation id on retry`() = runTest {
    transport.firstPushOutcome = AppliedButResponseLost(cursor = 12L)

    orchestrator.sync(SyncDocumentKind.LIBRARY)
    val pending = pendingRepository.single()
    assertEquals(1, pending.attemptCount)

    transport.nextPushOutcome = ReplaySuccess(cursor = 12L)
    orchestrator.sync(SyncDocumentKind.LIBRARY)

    assertTrue(pendingRepository.all().isEmpty())
    assertEquals(12L, cursorRepository.get("LIBRARY"))
    assertEquals(1, transport.uniqueMutationIds.size)
}
~~~

- [ ] **Step 2: Define client batch**

~~~kotlin
@Serializable
data class SupabaseMutationBatch(
    val mutationId: String,
    val originClientId: String,
    val domain: String,
    val baseCursor: Long,
    val operations: List<SyncMutation>,
)
~~~

Mutation ID is generated once per intent and persisted before network send.

- [ ] **Step 3: Implement stable client identity**

Store a random installation ID in `noBackupFilesDir`, separate from account ID. Reinstall gets a new client identity.

- [ ] **Step 4: Implement transport RPC calls**

Use current authenticated Supabase access token from AccountRepository.

Do not send any request when logged out. Logged-out sync reports `AuthorizationRequired` while leaving outbox/pending state intact.

- [ ] **Step 5: Reconcile canonical identity claims before generic document sync**

Before pushing canonical Library/title state for an authenticated account:
1. enumerate locally verified external identities;
2. call `sync_claim_external_identity` with the local CanonicalTitle ID;
3. if the returned ID differs, call Dev A `MergeCanonicalTitles.execute(survivorId = returnedId, duplicateId = localId)`;
4. export/diff only after the local graph has converged to the claimed ID.

Add a client test where device A proposes `canon-a`, device B proposes `canon-b` for `kitsu:1`, and both local graphs end on the same returned canonical ID without title-string matching.

- [ ] **Step 6: Implement orchestrator**

For each dirty document/domain:
1. pull current remote delta from accepted cursor;
2. materialize/apply remote changes through the existing adapter;
3. export current local document;
4. diff accepted materialized state → local state using `SyncDocumentDiffer`;
5. persist one mutation batch with base cursor;
6. push the exact persisted batch;
7. store returned cursor;
8. pull any remaining delta;
9. clear outbox only after the local/remote state is accepted.

Drive frontiers/journals are not used.

- [ ] **Step 7: Test logged-out local behavior and unresolved conflict retention**

Add both:

~~~kotlin
@Test
fun `logged out sync keeps local mutation pending`() = runTest {
    seedDirtyLibrary()
    accountState.value = AccountState.LoggedOut

    orchestrator.sync(SyncDocumentKind.LIBRARY)

    assertTrue(outboxRepository.get(SyncDocumentKind.LIBRARY) != null)
}

@Test
fun `field conflict preserves local operational state and dirty intent`() = runTest {
    seedLocalStatus("READING")
    transport.pushResult = PushResult.Conflict(fieldPath = "status", remoteValue = "COMPLETED")

    orchestrator.sync(SyncDocumentKind.LIBRARY)

    assertEquals("READING", localLibrary.status())
    assertNotNull(conflictRepository.get(SyncDocumentKind.LIBRARY))
    assertNotNull(outboxRepository.get(SyncDocumentKind.LIBRARY))
}
~~~

Independent non-conflicting fields from the same remote delta are still applied; only the divergent field remains unresolved.

- [ ] **Step 8: Run tests and commit**

A local Library change must enqueue sync work even while logged out; sync attempt does not erase it.

- [ ] **Step 7: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*SupabaseSyncOrchestratorTest' \
  :app:testDebugUnitTest --tests '*SupabaseSyncHttpTransportTest' \
  :app:compileDebugKotlin \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/sync \
  data/src/main/java/tachiyomi/data/tsuzuki/sync \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/supabase \
  domain/src/test/java/tachiyomi/domain/tsuzuki/sync \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/supabase
git commit -m "feat(tsuzuki): sync canonical state through Supabase"
~~~

---

### Task C4: Migrate sync domains and retire Google/Drive target path

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/adapter/CanonicalLibrarySyncAdapter.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/adapter/ChapterOverridesSyncAdapter.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/sync/CollectionsSyncAdapter.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/adapter/ContentPreferencesSyncAdapter.kt` after Dev B merge during final integration if the type is not yet available on Dev C branch.
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/drivesync/DriveSyncJob.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/drivesync/DriveSyncRuntime.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/drivesync/GoogleDriveAppDataTransport.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAccountHintStore.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAccountPicker.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthConnectResult.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthInteractiveCoordinator.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthSessionManager.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthorizationActivityBridge.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthorizationPlatform.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthorizationPlatformResult.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthorizationSession.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GoogleAuthorizedAccess.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GooglePlayAuthorizationActivityBridge.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/GooglePlayAuthorizationPlatform.kt`
- Delete: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/googleauth/NoBackupGoogleAccountHintStore.kt`
- Delete: `domain/src/main/java/tachiyomi/domain/tsuzuki/googleauth/model/GoogleAccountIdentity.kt`
- Delete: `domain/src/main/java/tachiyomi/domain/tsuzuki/googleauth/model/GoogleAuthFailure.kt`
- Delete: `domain/src/main/java/tachiyomi/domain/tsuzuki/googleauth/model/GoogleAuthState.kt`
- Delete: `domain/src/main/java/tachiyomi/domain/tsuzuki/googleauth/model/GoogleAuthorizationResult.kt`
- Delete: `domain/src/main/java/tachiyomi/domain/tsuzuki/googleauth/service/GoogleAuthStateMachine.kt`
- Delete: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/GoogleDriveAppDataTransportTest.kt`
- Delete: `domain/src/test/java/tachiyomi/domain/tsuzuki/googleauth/GoogleAuthStateMachineTest.kt`
- Delete/replace: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/DriveSyncTransport.kt`
- Remove target Settings entry: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsGoogleAccountScreen.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/AvailableSupabaseSyncAdaptersTest.kt`

**Interfaces:**
- Produces: target sync domains without Drive/Google dependencies.
- Consumes: SupabaseSyncOrchestrator.

- [ ] **Step 1: Write adapter inventory test**

Assert active sync kinds include canonical Library, Collections, Chapter Overrides, reading progress/Continue Reading state, and later Content Preferences.

Assert `SOURCE_MAPPINGS` is no longer a target sync document because provider bindings are recomputable/device-runtime state.

- [ ] **Step 2: Route SyncRuntimeController to Supabase orchestrator**

Manual Sync Now and background jobs use the account-aware Supabase runtime.

Logged out UI state is “Cloud sync off / sign in to enable”, not an error banner blocking local usage.

- [ ] **Step 3: Remove Google account/Drive runtime wiring**

Delete Google/Drive DI bindings, jobs, account settings route, and app-data transport.

Do not write a Drive importer or dual-write bridge.

- [ ] **Step 4: Preserve old sync domain models only if still used by local diff/conflict logic**

Remove `SyncReplicaJournal`, `SyncFrontier`, manifest/Drive-specific codecs only after reference search proves no target caller remains.

- [ ] **Step 5: Add no-Google regression test/search gate**

Test compilation without Google auth Tsuzuki classes and run:

~~~bash
git grep -n "SettingsGoogleAccountScreen\|GoogleDriveAppDataTransport\|DriveSyncTransport" -- app domain data
~~~

Expected: no target runtime references.

- [ ] **Step 7: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*AvailableSupabaseSyncAdaptersTest' \
  :app:compileDebugKotlin \
  spotlessCheck
git add -A app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki \
  app/src/main/java/eu/kanade/presentation/more/settings/screen \
  domain/src/main/java/tachiyomi/domain/tsuzuki \
  data/src/main/java/tachiyomi/data/tsuzuki/sync \
  domain/src/test/java/tachiyomi/domain/tsuzuki/sync
git commit -m "refactor(tsuzuki): retire Google Drive sync"
~~~

---

### Task C5: Make Home empty-by-default and driven by Continue Reading + Collections

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/home/model/HomeModels.kt`
- Replace behavior: `domain/src/main/java/tachiyomi/domain/tsuzuki/home/interactor/GetHomeCatalogFeed.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/home/interactor/GetConfiguredHomeSections.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/home/TsuzukiHomeScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/tsuzuki/home/TsuzukiHomeScreen.kt`
- Reuse: `domain/src/main/java/tachiyomi/domain/tsuzuki/collections/**`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/home/repository/ContinueReadingVisibilityRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/home/ContinueReadingVisibilityRepositoryImpl.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/home/GetConfiguredHomeSectionsTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/home/TsuzukiHomeScreenModelTest.kt`

**Interfaces:**
- Produces: `HomeSection` list built from user Collections plus system Continue Reading.
- Consumes: existing Collections execution engine and `ObserveHomeContinueReading`.

- [ ] **Step 1: Write fresh-install Home test**

~~~kotlin
@Test
fun `home has no discovery rows when user has no collections`() = runTest {
    val sections = getConfiguredHomeSections.execute()
    assertTrue(sections.isEmpty())
}
~~~

- [ ] **Step 2: Write Continue Reading emergence test**

~~~kotlin
@Test
fun `continue reading appears only after actual reading progress`() = runTest {
    assertTrue(screenModel.state.value.continueReading.isEmpty())

    progressRepository.record(partialProgress("chapter-1"))

    assertEquals(1, screenModel.awaitState().continueReading.size)
}
~~~

- [ ] **Step 3: Define Home section model**

~~~kotlin
sealed interface HomeSection {
    data class CollectionSection(
        val collectionId: String,
        val title: String,
        val rows: List<HomeRow>,
    ) : HomeSection
}
~~~

Continue Reading remains a dedicated system field/section, not a user Collection row.

- [ ] **Step 4: Remove automatic Kitsu refresh**

Delete `refresh()` from `TsuzukiHomeScreenModel.init`.

Home does not fetch Trending/Popular unless the user has created/configured Collections whose provider query requests them.

- [ ] **Step 5: Add new-chapter badge and Continue Reading hide semantics**

Consume canonical chapter update state so each Continue Reading card exposes the count of newly observed, not-yet-acknowledged chapters. Reading a new chapter decrements that count.

Add “Remove from Continue Reading” as a local user-state action that suppresses the item from Home while preserving:
- canonical read flags;
- page progress;
- Library membership;
- tracker state.

Starting/resuming reading after the suppression point may make the title eligible again according to a newer progress timestamp.

- [ ] **Step 6: Make Collection provider registry capability-driven**

After Dev A merge, resolve a CollectionList `providerId` through enabled Integration discovery/search capability adapters instead of hardcoded `KitsuCollectionQueryProvider`.

If provider is disabled, render that configured row as unavailable without deleting its definition.

- [ ] **Step 6: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*GetConfiguredHomeSectionsTest' \
  :app:testDebugUnitTest --tests '*TsuzukiHomeScreenModelTest' \
  :app:compileDebugKotlin \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/home \
  domain/src/main/java/tachiyomi/domain/tsuzuki/collections \
  data/src/main/java/tachiyomi/data/tsuzuki/collections \
  app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/home \
  app/src/main/java/eu/kanade/presentation/tsuzuki/home \
  domain/src/test/java/tachiyomi/domain/tsuzuki/home \
  app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/home
git commit -m "feat(tsuzuki): make Home user composed"
~~~

---

### Task C6: Add Account and Home/Collections Settings destinations

**Files:**
- Create: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAccountScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/account/TsuzukiAccountScreenModel.kt`
- Modify/Reuse: `app/src/main/java/eu/kanade/presentation/tsuzuki/collections/CollectionsScreen.kt`
- Create: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiHomeScreen.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/account/TsuzukiAccountScreenModelTest.kt`

**Interfaces:**
- Produces: standalone Account and Home/Collections settings destinations.
- Consumes: AccountRepository, CollectionStore/management interactors, SyncRuntimeController.

- [ ] **Step 1: Write optional-account UI state tests**

Logged out screen offers:
- Create account;
- Log in;
- local-only explanation.

It does not show a blocking modal on app start.

Authenticated screen shows e-mail, Sync Now, logout.

- [ ] **Step 2: Implement e-mail/password forms**

Registration and login validate nonblank e-mail/password client-side, map backend errors to typed screen errors, and never log passwords/tokens.

- [ ] **Step 3: Implement Home/Collections settings entry**

Expose the existing collection editor/manager here.

Creating the first collection is what causes the first configurable discovery section to appear on Home.

- [ ] **Step 4: Run tests and commit**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*TsuzukiAccountScreenModelTest' \
  :app:compileDebugKotlin \
  spotlessCheck
git add app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAccountScreen.kt \
  app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiHomeScreen.kt \
  app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/account \
  app/src/main/java/eu/kanade/presentation/tsuzuki/collections \
  app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/account
git commit -m "feat(tsuzuki): add optional account and Home settings"
~~~

---

### Task C7: Final integration-steward merge and four-tab shell

**Prerequisite:** Dev A and Dev B branches are already merged together and green on `tsuzuki/runtime-v2-integration`.

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/tsuzuki/library/CanonicalLibraryScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`
- Modify as needed: `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
- Consume from Dev A: `TsuzukiSearchTab`, `SettingsTsuzukiIntegrationsScreen`, `CanonicalTitleScreen`
- Consume from Dev B: `SettingsTsuzukiAddonsScreen`, content selector route
- Create if needed: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/settings/TsuzukiSettingsTab.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/home/TsuzukiNavigationContractTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/library/TsuzukiLibraryContractTest.kt`

**Interfaces:**
- Produces: final product navigation.
- Consumes all three workstreams.

- [ ] **Step 1: Merge A+B integration branch into Dev C**

~~~bash
git switch tsuzuki/runtime-v2-dev-c
git merge tsuzuki/runtime-v2-integration
~~~

Resolve only genuine shared-shell/config conflicts. Do not rewrite A/B-owned logic during conflict resolution.

- [ ] **Step 2: Write navigation contract test**

Assert exactly these target tabs:

~~~kotlin
listOf(
    TsuzukiHomeTab,
    TsuzukiSearchTab,
    LibraryTab,
    TsuzukiSettingsTab,
)
~~~

Assert `UpdatesTab`, `HistoryTab`, and `BrowseTab` are absent from `HomeScreen.TABS`.

- [ ] **Step 3: Wire four-tab shell**

Target bottom navigation:
- Home;
- Search;
- Library;
- Settings.

Keep legacy routes/classes only if internal compatibility still references them. They must not be primary tabs.

- [ ] **Step 4: Finalize canonical Library contract**

Add a test that the Library presentation model/card exposes canonical title, status, categories and reading state but does **not** expose `SOURCE_ONLY`, source ID, per-title language, source order, or reading-source configuration actions.

Library item click navigates to Dev A `CanonicalTitleScreen(canonicalTitleId)`.

Keep categories/status filters; remove the old source-configuration entry points from the target Library surface.

- [ ] **Step 5: Build Settings landing page**

Settings entries include:
- Account → C screen;
- Integrations → A screen;
- Add-ons → B screen;
- Home & Collections → C screen;
- Reading;
- Downloads;
- Sync/account status;
- Appearance and retained app settings.

Remove Google Account entry.

- [ ] **Step 6: Add post-merge modular-settings sync adapters**

After A/B types exist, add sync adapters for:
- Integration enabled/config state, excluding Integration credentials/tokens;
- per-title preferred Add-on;
- global automatic fallback and preferred languages;
- Add-on desired/enabled package IDs as non-executable intent only.

On a new device, a desired APK-backed Add-on that is not installed is displayed as needing installation. Sync must not call ExtensionManager.install or trust an extension automatically.

Do not sync ContentBinding runtime payloads, downloaded payloads, or executable trust decisions.

- [ ] **Step 7: Run app/integration tests**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*TsuzukiNavigationContractTest' \
  :app:testDebugUnitTest --tests '*TsuzukiLibraryContractTest' \
  :domain:testDebugUnitTest \
  :data:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:compileDebugKotlin \
  verifySqlDelightMigration \
  spotlessCheck
~~~

- [ ] **Step 8: Commit**

~~~bash
git add -A
git commit -m "feat(tsuzuki): integrate modular four-tab runtime [ci-full] [apk]"
~~~

Require full CI green before device smoke.

---

### Task C8: Physical-device smoke and final program handoff

**Files:**
- No production changes unless the smoke demonstrates a reproducible regression in Dev C-owned integration code.

**Interfaces:**
- Produces: release-candidate evidence.
- Consumes: integrated A+B+C branch.

- [ ] **Step 1: Install the CI-built ARM64 APK**

Use the APK from the exact C7 commit.

- [ ] **Step 2: Smoke fresh local-only flow**

Verify:
1. launch succeeds without account;
2. Home has no discovery rows;
3. Search shows Integration setup CTA;
4. Library is usable;
5. Settings has Account/Integrations/Add-ons/Home & Collections;
6. there is no Google login/Drive setting;
7. there are no Updates/History/Browse primary tabs.

- [ ] **Step 3: Smoke configured reading flow**

Enable Kitsu, install one content Add-on, search Dandadan, add one canonical item, open chapter, choose content, read, exit, reopen next chapter.

Verify remembered provider is title-specific.

- [ ] **Step 4: Smoke fallback**

Disable/uninstall preferred content Add-on:
- automatic fallback off → selector;
- re-enable another option and confirm manual selection does not silently change preference without confirmation.

- [ ] **Step 5: Smoke optional cloud**

Register/login using Supabase e-mail/password, press Sync Now, make Library/progress change, confirm another test installation/account session receives delta.

Logout and verify local Library/Reader still work.

- [ ] **Step 6: Smoke no-Drive migration policy**

No UI offers Drive import. Existing local data remains present after enabling Supabase sync and bootstraps normally.

- [ ] **Step 7: Emit final handoff from actual git/CI evidence**

Run:

~~~bash
git rev-parse HEAD
git status --short
git log -5 --oneline
~~~

Report exact CI run, APK artifact/run, smoke results, and any remaining transport subproject such as production torrent-engine integration.
