# Tsuzuki Drive Replica Journals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unsafe shared mutable Drive sync files with protocol-v2 single-writer causal replica journals, eliminating SYNC-P2 and SYNC-P3 without silent last-write-wins.

**Architecture:** Each installation owns one Drive journal per logical document kind and only updates its own journal. Journals contain append-only causal mutation batches plus an optional immutable legacy-v1 genesis. A pure materializer reduces all journals deterministically into one logical document and explicit conflict ledger; SQLDelight stores the accepted frontier and local replica ownership; Drive transport supplies appProperties and idempotent generated-ID creation.

**Tech Stack:** Kotlin, kotlinx.serialization, SQLDelight, OkHttp, Google Drive REST v3, Kotest/JUnit, GitHub Fast CI.

**Spec:** `docs/superpowers/specs/2026-09-20-tsuzuki-drive-replica-journals-design.md`

## Global Constraints

- Google remains optional.
- Local SQLDelight state remains authoritative for immediate app behavior.
- Cloud failure never rolls back a successful local action.
- Sync is not backup.
- Only `drive.appdata` is used; no broader Drive scope.
- Access tokens remain transient and must never be persisted or logged.
- Tracker tokens remain device-local.
- No blind last-write-wins.
- Independent edits merge automatically.
- Same-property concurrent edits become explicit conflicts.
- Deletions propagate through tombstones.
- No device may update another device's v2 replica journal.
- Shared protocol-v1 files become read-only migration inputs.
- No Milestone 13, tracker-reconciliation, Reader, downloader, source-network, or visual-redesign work.
- Every implementation task ends with Fast CI green before the next task starts.
- Final integration requires a `[full-ci]` checkpoint; APK remains part of the already-pending human MVP-V3 gate, not this protocol change.

## Review Focus

1. **Three replicas edit the same property concurrently:** materialization must preserve every distinct contender in deterministic conflict entries and must not select a winner.
2. **A journal frontier references history that is absent:** treat the journal set as malformed/unsafe rather than silently accepting an incomplete causal history.
3. **A record is deleted and later recreated causally:** later field mutations must reactivate the record; a concurrent delete/edit must remain `DELETE_EDIT`.
4. **A v1 legacy file changes after v2 journals exist:** fail closed as mixed protocol; do not treat the newer v1 bytes as another current replica.
5. **A create response is lost after Drive accepted the reserved ID:** retry must reuse the persisted ID, adopt the existing matching shard after HTTP 409, and never generate a second owner shard.

---

### Task 1: Protocol-v2 causal models, differ, codec, and deterministic materializer

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncFrontier.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncMutation.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncReplicaJournal.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncReplicaMaterialization.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SyncReplicaJournalCodec.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SyncDocumentDiffer.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SyncReplicaMaterializer.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SyncExecutionContext.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/SyncReplicaJournalCodecTest.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/SyncDocumentDifferTest.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/SyncReplicaMaterializerTest.kt`

**Interfaces:**
- Modify `SyncRevisionSource` to:
```kotlin
interface SyncRevisionSource {
    val deviceId: String
    fun nextRevision(): SyncRevision
}
```
- Produce:
```kotlin
@Serializable
data class SyncFrontier(
    val entries: Map<String, Long> = emptyMap(),
) {
    fun observes(revision: SyncRevision): Boolean
    fun advance(revision: SyncRevision): SyncFrontier
    fun mergedWith(other: SyncFrontier): SyncFrontier
}

@Serializable
sealed interface SyncMutation {
    val recordId: String
    val recordUpdatedAtEpochMillis: Long

    @Serializable
    @SerialName("set_field")
    data class SetField(
        override val recordId: String,
        val propertyPath: List<String>,
        val value: JsonElement,
        override val recordUpdatedAtEpochMillis: Long,
    ) : SyncMutation

    @Serializable
    @SerialName("remove_field")
    data class RemoveField(
        override val recordId: String,
        val propertyPath: List<String>,
        override val recordUpdatedAtEpochMillis: Long,
    ) : SyncMutation

    @Serializable
    @SerialName("delete_record")
    data class DeleteRecord(
        override val recordId: String,
        override val recordUpdatedAtEpochMillis: Long,
        val deletedAtEpochMillis: Long,
    ) : SyncMutation
}

@Serializable
data class SyncMutationBatch(
    val revision: SyncRevision,
    val observed: SyncFrontier,
    val generatedAtEpochMillis: Long,
    val mutations: List<SyncMutation>,
)

@Serializable
data class SyncReplicaGenesis(
    val sourceRemoteId: String,
    val sourceRevisionToken: String?,
    val document: SyncDocumentEnvelope,
)

@Serializable
data class SyncReplicaJournal(
    val protocolVersion: Int = 2,
    val kind: SyncDocumentKind,
    val ownerDeviceId: String,
    val genesis: SyncReplicaGenesis? = null,
    val batches: List<SyncMutationBatch>,
)

sealed interface SyncReplicaMaterializationResult {
    data class Success(
        val document: SyncDocumentEnvelope,
        val frontier: SyncFrontier,
        val conflicts: List<SyncConflict>,
    ) : SyncReplicaMaterializationResult

    data class Failure(
        val failure: SyncFailure,
    ) : SyncReplicaMaterializationResult
}
```
- `SyncDocumentDiffer.diff(base, current)` compares record content/tombstone state only; export-generated record/document revisions must not create mutations.
- `SyncReplicaMaterializer.materialize(kind, schemaVersion, journals, materializedAtEpochMillis, visibleFrontier = null)` is pure and listing-order independent. When `visibleFrontier` is non-null it materializes only batches causally visible through that frontier; this is used to reconstruct the local device's already-published causal state after an ambiguous write.

- [ ] **Step 1: Write RED tests for frontier and journal validation**

Add codec/model tests proving:
- blank owner ID rejected;
- batch owner must equal journal owner;
- batch sequence strictly increases;
- negative frontier values rejected;
- same-owner observed sequence cannot be >= current batch sequence;
- non-null genesis kind/schema must match journal kind;
- canonical encode/decode is deterministic.

Push only tests/models needed to compile test declarations and confirm Fast CI unit tests fail for missing implementation behavior.

- [ ] **Step 2: Implement minimal frontier/journal models and canonical codec**

Use `canonicalizeSyncJson()` exactly as the existing document codec does. Decode malformed/invalid journals as `MALFORMED_REMOTE_DOCUMENT`; encode invalid local values as `MALFORMED_DOCUMENT`.

Run/push Fast CI. Acceptance: new codec/model tests green; existing protocol tests remain green.

- [ ] **Step 3: Write RED differ tests**

Cover:
```text
missing record -> SetField for every leaf
changed primitive -> SetField
removed property -> RemoveField
nested object -> leaf deltas only
array -> atomic SetField
record disappears -> DeleteRecord
tombstone -> later active record -> field mutations that recreate
same fields with different SyncRevision -> no mutation
same fields with different generatedAt -> no mutation
```

For active mutations assert the source record `updatedAtEpochMillis` is preserved. For delete assert both update/delete timestamps are preserved.

- [ ] **Step 4: Implement `SyncDocumentDiffer`**

Requirements:
- deterministic record/path ordering;
- compare `fields`, tombstone status, record timestamps only where needed for emitted mutation metadata;
- do not diff `SyncRecordEnvelope.revision` or `SyncDocumentEnvelope.revision/generatedAt`;
- recurse only through JSON objects; arrays/primitives are atomic.

Run/push Fast CI. Acceptance: differ tests and existing adapter round-trip tests green.

- [ ] **Step 5: Write RED materializer tests for causal semantics**

At minimum include:
```text
fresh A + fresh B, different records -> both survive
A and B change different fields concurrently -> merge
A and B change same field differently -> FIELD_DIVERGENCE
three distinct concurrent values -> deterministic pairwise conflict set contains all contenders
concurrent equivalent value -> collapse
delete causally after field -> tombstone
field causally after delete -> active recreated record
delete concurrent with field -> DELETE_EDIT
stale mutation causally dominated -> cannot resurrect old value
three partially connected replica histories -> same result for every journal listing permutation
frontier references unavailable batch -> MALFORMED_REMOTE_DOCUMENT
journals disagree on non-null legacy genesis -> unsafe/malformed failure
identical copied legacy genesis + independent deltas -> no false conflict
```

- [ ] **Step 6: Implement `SyncReplicaMaterializer`**

Algorithm constraints:
- validate all journals before reducing;
- identify one empty or identical immutable genesis;
- union batches by exact `SyncRevision`; duplicate revision with different content is malformed;
- verify observed frontier entries do not exceed available causal history (except the immutable genesis identity if represented separately);
- determine happens-before solely from frontiers/revisions;
- for each record/property retain causally maximal mutations;
- equivalent concurrent values collapse;
- distinct maximal values create conflicts; deterministic revision sorting is only for stable conflict serialization;
- materialized record `updatedAtEpochMillis` is max timestamp among contributing winning/equivalent active mutations after causal winner selection;
- timestamps never choose a value;
- tombstone state follows the delete rules in the spec;
- output frontier is the per-device max accepted sequence;
- output document ordering/canonical content is deterministic.

Run/push Fast CI.

- [ ] **Step 7: Update every `SyncRevisionSource` implementation/fake with `deviceId`**

Production `AndroidSyncRevisionSource.deviceId` exposes the already-loaded random ID. Test fakes use stable IDs such as `test-device` and preserve existing monotonic sequence behavior.

Run/push Fast CI and commit:
```text
feat(sync): add causal replica journal core
```

---

### Task 2: Persist accepted causal frontier and local replica ownership

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncReplicaState.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/repository/SyncReplicaRepository.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncStoredState.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_sync.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/26.sqm`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/sync/SyncRepositoriesImpl.kt`
- Modify: `data/src/test/java/tachiyomi/data/tsuzuki/sync/SyncRepositoriesImplTest.kt`

**Interfaces:**
```kotlin
data class SyncReplicaState(
    val documentKind: SyncDocumentKind,
    val ownerDeviceId: String,
    val reservedRemoteId: String?,
    val lastRemoteRevisionToken: String?,
    val nextSequence: Long,
)

interface SyncReplicaRepository {
    suspend fun get(
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
    ): SyncReplicaState?
    suspend fun put(state: SyncReplicaState)
    suspend fun clear(
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
    )
}
```

Extend `SyncStoredState`:
```kotlin
data class SyncStoredState(
    val documentKind: SyncDocumentKind,
    val acceptedBase: SyncDocumentEnvelope?,
    val acceptedFrontier: SyncFrontier = SyncFrontier(),
    val remoteRevision: SyncRemoteRevision?, // retained only for v1 migration evidence
    val lastSuccessfulSyncAtEpochMillis: Long?,
)
```

SQL:
```sql
ALTER TABLE tsuzuki_sync_state
ADD COLUMN accepted_frontier_json TEXT;

CREATE TABLE tsuzuki_sync_replicas(
    document_kind TEXT NOT NULL,
    owner_device_id TEXT NOT NULL,
    reserved_remote_id TEXT,
    last_remote_revision_token TEXT,
    next_sequence INTEGER NOT NULL,
    PRIMARY KEY(document_kind, owner_device_id)
);
```

- [ ] **Step 1: Write RED repository/migration tests**

Add tests for:
- state frontier round-trip;
- replica state round-trip;
- reserved ID persists before any remote create;
- next sequence survives repository recreation;
- a different current device ID does not load or reuse the old owner's replica row;
- migration 26 preserves existing v1 state rows and leaves frontier empty/null-compatible;
- P1 repeated-upsert regressions from migration 25 remain green.

- [ ] **Step 2: Implement migration 26 and SQLDelight queries**

Add:
```text
getTsuzukiSyncReplica
upsertTsuzukiSyncReplica
deleteTsuzukiSyncReplica
```

Encode frontier using the same private deterministic JSON configuration as sync state. Existing rows with null `accepted_frontier_json` map to `SyncFrontier()`.

Run/push Fast CI. SQLDelight migration verification must be green.

- [ ] **Step 3: Implement `SyncReplicaRepositoryImpl` and state frontier persistence**

Use one transaction for each logical state write. Validate `nextSequence >= 1`, nonblank owner, and nonblank optional remote/revision IDs. A restored database with a new `noBackupFilesDir` device ID gets a new row and must never resume the stale owner's reserved remote ID.

Run/push Fast CI and commit:
```text
feat(sync): persist replica journal ownership
```

---

### Task 3: Make Drive transport replica-aware and idempotent

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncRemoteFile.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/model/SyncFailure.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/DriveSyncTransport.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/drivesync/GoogleDriveAppDataTransport.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/drivesync/GoogleDriveAppDataTransportTest.kt`

**Interfaces:**
Extend remote metadata:
```kotlin
data class SyncRemoteFile(
    val remoteId: String,
    val name: String,
    val mimeType: String?,
    val revision: SyncRemoteRevision,
    val protocolVersion: Int? = null,
    val logicalKind: SyncDocumentKind? = null,
    val ownerDeviceId: String? = null,
)
```

Replace shared-write transport methods with:
```kotlin
interface DriveSyncTransport {
    suspend fun listFiles(): SyncTransportResult<List<SyncRemoteFile>>
    suspend fun download(file: SyncRemoteFile): SyncTransportResult<SyncRemoteContent>
    suspend fun generateFileId(): SyncTransportResult<String>
    suspend fun createReplica(
        remoteId: String,
        documentKind: SyncDocumentKind,
        ownerDeviceId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>
    suspend fun updateOwnedReplica(
        file: SyncRemoteFile,
        ownerDeviceId: String,
        content: String,
    ): SyncTransportResult<SyncRemoteFile>
    suspend fun getFile(remoteId: String): SyncTransportResult<SyncRemoteFile>
}
```

Add typed reasons if needed by the orchestrator:
```text
DUPLICATE_REPLICA
MIXED_PROTOCOL
```
Do not create an overly broad exception taxonomy; malformed body/metadata remains `MALFORMED_REMOTE_DOCUMENT`.

- [ ] **Step 1: Write RED transport tests for metadata and generated IDs**

Prove:
- list requests `fields=...appProperties...`;
- v2 appProperties map to protocol/kind/owner;
- legacy files with no appProperties remain legacy metadata rather than being guessed from filename;
- malformed protocol/kind appProperty values fail closed or are represented in a way the orchestrator rejects explicitly;
- `generateFileId()` sends `count=1&space=appDataFolder&type=files` (omit `type` if the current REST discovery used by tests rejects it; `space=appDataFolder` and one returned ID are mandatory);
- malformed/empty generateIds responses fail as `MALFORMED_REMOTE_DOCUMENT`.

- [ ] **Step 2: Write RED create tests**

Expected multipart metadata includes:
```json
{
  "id": "<reserved-id>",
  "name": "tsuzuki-v2-...",
  "parents": ["appDataFolder"],
  "mimeType": "application/json",
  "appProperties": {
    "tsuzukiProtocol": "2",
    "logicalKind": "LIBRARY",
    "ownerDeviceId": "device:A"
  }
}
```

Assert token/content are absent from logs and filename is diagnostic only.

- [ ] **Step 3: Implement generate/create/get metadata support**

Use official Drive v3 behavior verified in the design:
- generated IDs are valid for `appDataFolder`;
- successful create retry with the same generated ID returns 409 instead of creating a duplicate;
- `appProperties` are application-private metadata.

Keep the existing logging-interceptor stripping unchanged.

Run/push Fast CI.

- [ ] **Step 4: Write RED owned-update tests**

Prove:
- local owner may update its shard;
- foreign owner is rejected before PATCH;
- metadata/body owner mismatch is never normalized silently;
- remote-version preflight mismatch still returns `REMOTE_CHANGED` as defense-in-depth;
- no shared legacy file is accepted by `updateOwnedReplica`.

- [ ] **Step 5: Implement `updateOwnedReplica`**

Validate `protocolVersion == 2`, matching logical kind metadata, and `ownerDeviceId == requested owner` before PATCH. Keep GET-version preflight, but do not present it as CAS correctness; single-writer ownership is the correctness boundary.

Run/push Fast CI and commit:
```text
feat(sync): add single-writer Drive replica transport
```

---

### Task 4: Implement protocol-v2 cycle orchestration and v1 bootstrap

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SyncReplicaBootstrap.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/sync/service/SyncCycleOrchestrator.kt`
- Modify: `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/SyncCycleOrchestratorTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/drivesync/DriveSyncRuntime.kt`

**Interfaces:**
Add `SyncReplicaRepository`, `SyncReplicaJournalCodec`, `SyncDocumentDiffer`, and `SyncReplicaMaterializer` to the orchestrator constructor.

Create a focused bootstrap helper:
```kotlin
class SyncReplicaBootstrap(
    private val documentCodec: SyncDocumentCodec,
) {
    fun resolveGenesis(
        documentKind: SyncDocumentKind,
        legacyFiles: List<SyncRemoteContent>,
        journals: List<SyncReplicaJournal>,
    ): SyncReplicaBootstrapResult
}
```

The helper:
- accepts zero legacy files as empty genesis;
- accepts exactly one valid legacy file only when no v2 journal already establishes a different genesis;
- rejects duplicate legacy logical files;
- validates a visible legacy file against genesis embedded in existing v2 journals;
- never writes/deletes v1 files.

- [ ] **Step 1: Replace current fake transport contract in orchestrator tests**

Update `FakeTransport` to model:
- multiple v2 replica files;
- reserved IDs;
- per-file contents;
- create-by-reserved-ID;
- local-owned update;
- optional ambiguous-create 409/adoption path;
- counters identifying which remote IDs were written.

Do not implement production orchestrator behavior yet.

- [ ] **Step 2: Add RED bootstrap tests**

Cover:
- exactly one v1 `library.json` + no v2 -> legacy genesis accepted;
- duplicate v1 `library.json` -> fail closed;
- existing v2 journal with copied genesis + unchanged visible v1 -> accepted;
- existing v2 genesis + changed v1 revision/content -> `MIXED_PROTOCOL`;
- v2 journals reconstruct state when legacy file is absent;
- two devices migrating the same unchanged legacy genesis with independent local edits merge without false conflicts;
- one device unchanged from legacy and another changing a legacy field accepts the real change rather than reporting a false concurrent edit;
- no shared `manifest.json` write occurs.

- [ ] **Step 3: Implement `SyncReplicaBootstrap`**

Use exact legacy filenames only for v1 discovery. V2 correctness uses appProperties and decoded journal metadata. When migrating a device that already has v1 `SyncStoredState`, run the existing `ThreeWaySyncMerger` once with `base = stored acceptedBase`, `local = adapter export`, and `remote = legacy genesis`; if conflict-free, encode only `legacy genesis -> merged result` as the first v2 local delta. With no stored v1 base, `base = null` remains conservative. This prevents unchanged legacy values from becoming false concurrent edits during two-device migration.

Run/push Fast CI.

- [ ] **Step 4: Add RED cycle tests for P2/P3**

Required tests:
```text
two fresh devices list empty concurrently:
  A creates only A shard
  B creates only B shard
  later pull sees both and converges

A and B update concurrently:
  A never PATCHes B remote ID
  B never PATCHes A remote ID

ambiguous create:
  local reserved ID persisted
  create returns simulated lost-response condition / retry 409
  getFile(reservedId) matches owner metadata
  orchestrator adopts same file
  no second generateFileId call

duplicate same-owner v2 shards:
  fail closed before domain apply

remote-owned shard changed:
  local device downloads it
  never updates it
```

- [ ] **Step 5: Add RED cycle tests for causal local publication**

Cover:
- outbox dirty -> diff accepted base against current export -> one candidate local batch;
- candidate batch `observed` equals accepted frontier;
- batch revision uses local device ID and persisted next sequence;
- successful create/update advances persisted replica sequence/revision;
- failed write leaves outbox dirty and does not advance accepted state;
- sequence allocation is persisted before remote write and gaps are allowed;
- after an ambiguous prior write, pulled local-owned batches beyond the accepted frontier are recognized as already-published local history and are not emitted again;
- reconstructing that already-published local causal state uses the newest local batch's observed frontier plus its own revision, excluding remote batches the device had not observed when the local edit occurred;
- only a semantic diff between that reconstructed local causal state and the current domain export may create a newer batch;
- conflict materialization persists conflict ledger and does not silently apply a winner;
- conflict-free remote changes apply through adapter;
- adapter-triggered outbox dirtiness is cleared only after accepted v2 state is persisted;
- cancellation propagates unchanged.

- [ ] **Step 6: Implement protocol-v2 `SyncCycleOrchestrator`**

Per document:
1. classify listed files into v2 journals vs exact legacy v1 file;
2. reject duplicate v2 `(kind, ownerDeviceId)`;
3. download/decode all relevant journals;
4. resolve/validate genesis;
5. materialize remote v2 state;
6. load accepted state/frontier and the current owner's replica state;
7. reconcile local `nextSequence` to at least one greater than the max sequence already present in the pulled local-owned journal;
8. if the local-owned journal contains batches beyond the accepted frontier, reconstruct the local causal published state by materializing only history visible through the newest such batch's observed frontier plus that batch revision;
9. export current adapter document;
10. choose the local causal diff base: reconstructed already-published local state when present, otherwise accepted base; for first v2 migration use the resolved genesis/legacy merge rules below;
11. create a new local batch only when the outbox/first-sync rules say local state is dirty and the semantic diff from that causal base is non-empty; its `observed` frontier is the causal base frontier, not the full freshly pulled remote frontier;
12. persist sequence allocation before attempting a new remote write; sequence gaps are valid;
13. materialize remote journals + candidate local batch;
14. if no local journal exists, reserve and persist a Drive ID before create;
15. write only the local-owned journal, or skip write when no new local batch exists;
16. on create 409, fetch/adopt only if protocol/kind/owner metadata match the reserved ID;
17. persist conflicts when present; never choose a conflicting winner;
18. when conflict-free, apply materialized state locally if content differs, persist accepted state/frontier, clear conflicts and outbox;
19. never write shared v1 files or shared manifest.

Keep the outer `Mutex`, retry policy, cycle-wide failure classification, and cancellation handling.

- [ ] **Step 7: Wire runtime dependencies**

Update `DriveSyncRuntime` to inject the replica repository and instantiate the v2 codec/differ/materializer/bootstrap. `AndroidSyncRevisionSource.deviceId` is the single source of local replica identity.

Run/push Fast CI and commit:
```text
fix(sync): replace shared Drive writes with replica journals
```

---

### Task 5: Adapter regression matrix and protocol hardening

**Files:**
- Modify only if tests demonstrate a real defect:
  - `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/adapter/AvailableSyncDocumentAdaptersTest.kt`
  - `domain/src/test/java/tachiyomi/domain/tsuzuki/sync/adapter/ChapterOverridesSyncAdapterTest.kt`
  - existing Library/Source Mapping adapter tests
  - `data/src/test/java/tachiyomi/data/tsuzuki/sync/CollectionsSyncAdapterTest.kt`
- Modify protocol implementation files from Tasks 1–4 only for demonstrated failures.

- [ ] **Step 1: Add end-to-end in-memory protocol tests across real adapters**

For each currently enabled v2 document kind:
- Library;
- Source Mappings/preferences;
- Collections;
- Chapter Overrides/reader preference.

Test export -> journal diff -> materialize -> apply -> re-export, and assert semantic document content is stable even though adapters generate fresh revision metadata.

- [ ] **Step 2: Add adversarial cross-kind regression tests**

Prove:
- generated revision metadata alone never re-dirties semantic sync;
- tombstoned Collections/Chapter Overrides survive round-trip;
- source preference arrays remain atomic values and do not merge element-by-element;
- malformed record schema fails before partial domain application;
- no `FALLBACK_PROGRESS` adapter becomes enabled early.

- [ ] **Step 3: Run Fast CI and fix only demonstrated regressions**

Acceptance requires all four independent Fast CI jobs green:
```text
spotlessCheck
:app:compileDebugKotlin
testDebugUnitTest
verifySqlDelightMigration
```

Commit:
```text
test(sync): harden replica journal regressions
```

---

### Task 6: Re-audit SYNC-P2/P3 and integration verification

**Files:**
- Modify: `docs/superpowers/plans/2026-09-19-tsuzuki-post-mvp-deep-audit.md` or its audit-ledger section/file if the existing plan specifies another ledger destination.
- No production changes unless re-audit demonstrates a concrete defect.

- [ ] **Step 1: Reproduce the original P2 attack against protocol v2**

Construct two independent orchestrators sharing one fake Drive store:
- both pull;
- B publishes;
- A publishes from stale prior observation.

Acceptance:
- A's request cannot target B's file ID;
- both immutable logical batches remain available;
- next pull materializes both or records explicit conflict;
- no remote bytes written by B are overwritten by A.

- [ ] **Step 2: Reproduce the original P3 bootstrap attack**

Two fresh devices list the same empty remote state and publish without seeing each other.

Acceptance:
- two distinct owner shards are valid;
- neither is classified as duplicate logical corruption;
- next pull converges;
- same-owner duplicate remains a hard failure.

- [ ] **Step 3: Re-audit protocol-v2 invariants**

Review diff specifically for:
- any code path that PATCHes a foreign owner;
- any code path that writes legacy shared filenames;
- any timestamp/device lexical order used as conflict winner;
- any network failure that clears outbox/accepted state too early;
- any logging interceptor that could emit token/private sync JSON;
- any frontier/reference validation gap that could silently drop causal history.

Document dismissed/confirmed findings with evidence.

- [ ] **Step 4: Require complete Fast CI green**

Do not proceed while any job is red.

- [ ] **Step 5: Trigger final Full Verify**

Create a checkpoint commit whose message includes:
```text
chore(sync): replica journal verification [full-ci]
```

Acceptance: release `assembleRelease -Pinclude-telemetry -Penable-updater` green.

- [ ] **Step 6: Update audit ledger**

Record:
```text
SYNC-P1 — fixed by 132c92a9a + migration 25
SYNC-P2 — fixed by protocol-v2 single-writer replica journals; include final commit/test evidence
SYNC-P3 — fixed by per-device shards + persisted generated Drive IDs; include final commit/test evidence
baseline SHA
final SHA
Fast CI run
Full Verify run
remaining human gate: physical MVP-V3 acceptance
remaining known risk: journal growth/compaction intentionally deferred
```

Do not merge automatically and do not start Milestone 13.
