# Tsuzuki Drive Sync Replica Journals — Design

Date: 2026-09-20  
Branch: `tsuzuki/post-mvp-deep-audit`  
Audit findings: SYNC-P2, SYNC-P3

## Decision

Replace the shared mutable Drive-file protocol with **single-writer replica journals**.

Each Tsuzuki installation owns one physical Drive file per logical document kind. Only that installation may mutate that file. The file stores an append-only logical journal of causal mutation batches produced by that device. Other installations only read it.

This is a protocol-v2 change. It is intentionally limited to sync protocol/transport/state. It does not change Library, Reader, Collections, source resolution, canonical identity, or tracker semantics.

## Why this change is required

The current protocol maps each logical document to one shared mutable Drive file such as `library.json`.

Two correctness failures are confirmed:

1. **SYNC-P2 — concurrent update overwrite.** A client reads Drive file version N, another device updates the file, then the first client PATCHes stale merged content. Drive v3 `files.update` does not expose a content compare-and-swap precondition in the API contract used by Tsuzuki, so the stale PATCH can overwrite the other device without a conflict being observed by the sync engine.
2. **SYNC-P3 — concurrent bootstrap duplication.** Two new devices can both list an empty `appDataFolder` and both create the same logical filename. Drive permits duplicate names, so the next cycle sees multiple files for one logical document and fails closed.

A second design problem appears if the shared file is replaced only by per-device `base + current` snapshots: after several partially connected devices advance from different causal bases, a new device may no longer possess a common base that permits a sound three-way merge. The protocol therefore needs causal operation history, not only the latest transition.

## Existing invariants preserved

The protocol must continue to satisfy all MVP-V3 invariants:

- Google is optional.
- Local SQLDelight state remains authoritative for immediate app behavior.
- Cloud failure never rolls back a successful local action.
- Sync is not backup.
- Only `drive.appdata` is used.
- Access tokens remain transient and are never persisted or logged.
- Tracker tokens remain device-local.
- Downloads, page caches, source inventories, and recomputable data are not synchronized.
- No blind last-write-wins is allowed.
- Independent edits merge automatically.
- Same-property concurrent edits become explicit conflicts.
- Deletions propagate through tombstones.
- Drive transport does not own merge semantics.

## External Drive capabilities used

Protocol v2 relies on two documented Drive v3 capabilities:

- `files.generateIds?space=appDataFolder` can reserve file IDs before creation. A generated ID can be supplied to `files.create`; retrying a successful create with the same ID returns HTTP 409 instead of creating another file.
- `File.appProperties` provides application-private key/value metadata retrievable through authenticated requests and usable in Drive queries.

References:

- https://developers.google.com/workspace/drive/api/reference/rest/v3/files/generateIds
- https://developers.google.com/workspace/drive/api/guides/create-file
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files
- https://developers.google.com/workspace/drive/api/guides/properties
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update

## Protocol identity

The existing Android sync device ID remains the replica identity:

```text
device:<UUID>
```

It is stored in `noBackupFilesDir`, so a restored/reinstalled app receives a new replica identity instead of impersonating the old installation.

For every synchronizable `SyncDocumentKind`, protocol v2 has at most one owned journal per device:

```text
(logical document kind, owner device ID) -> one physical Drive file
```

A device must never PATCH a journal owned by another device.

## Physical Drive file metadata

The filename is diagnostic only. Correctness uses `appProperties` and the reserved Drive file ID.

Recommended filename:

```text
tsuzuki-v2-<logical-kind>-<owner-key>.json
```

Required private `appProperties`:

```text
tsuzukiProtocol = "2"
logicalKind     = <SyncDocumentKind.name>
ownerDeviceId   = <device ID>
```

The owner key in the filename may be a stable digest/short representation of the device ID. It is not an identity source.

A listed v2 file whose metadata and decoded body disagree on protocol, logical kind, or owner is malformed and must fail closed.

## Stable file creation

File creation must be idempotent even when the HTTP response is lost.

Before the first create for a local `(documentKind, ownerDeviceId)`:

1. request one ID from `files.generateIds` with `space=appDataFolder`;
2. persist that reserved ID locally before the create request;
3. create the journal using the reserved ID and required `appProperties`;
4. if create returns HTTP 409, fetch the reserved ID;
5. if the existing file has the expected protocol/kind/owner metadata, adopt it as the successful prior create;
6. otherwise fail closed as malformed remote state.

A retry must reuse the same reserved ID. It must not call `generateIds` again merely because the prior create response was ambiguous.

## Local replica metadata

Add dedicated local persistence for physical replica ownership rather than overloading the accepted logical sync base.

Conceptually:

```text
tsuzuki_sync_replicas
- document_kind
- owner_device_id
- reserved_remote_id
- last_remote_revision
- next_sequence
PRIMARY KEY(document_kind, owner_device_id)
```

The table is device-local operational metadata. It is not synchronized. Replica ownership is keyed by both logical kind and owner device ID so a restored database paired with a new `noBackupFilesDir` device identity cannot accidentally resume writing the old device's remote shard. Old-owner rows may remain inert; the current device only queries its own key.

`next_sequence` is monotonic for that local document journal. Sequence gaps are valid. On startup/sync, a remote local-owned journal with a higher sequence than local metadata advances the local counter before any new batch is created.

The existing `tsuzuki_sync_state` continues to hold the last conflict-free materialized logical document, but protocol v2 extends that state with the accepted causal frontier.

## Journal envelope

Each physical v2 file stores one logical replica journal:

```kotlin
@Serializable
data class SyncReplicaJournal(
    val protocolVersion: Int = 2,
    val kind: SyncDocumentKind,
    val ownerDeviceId: String,
    val genesis: SyncReplicaGenesis? = null,
    val batches: List<SyncMutationBatch>,
)

@Serializable
data class SyncReplicaGenesis(
    val sourceRemoteId: String,
    val sourceRevisionToken: String?,
    val document: SyncDocumentEnvelope,
)
```

The list is ordered by the owner's strictly increasing sequence. A journal containing duplicate, decreasing, negative, or foreign-owner batch sequences is malformed.

`genesis` is normally null for accounts that start on protocol v2. During v1 migration it carries the immutable legacy base that all protocol-v2 deltas are interpreted against. Every v2 journal created while a legacy genesis exists copies the exact same genesis tuple. If non-null genesis values across journals disagree by legacy remote ID, revision token, document kind/schema, or canonical document content, the remote state is an unsafe mixed-protocol history and fails closed.

A journal update rewrites the physical file content, but it only appends to the logical `batches` sequence. Existing batches and genesis must never be modified or removed by protocol-v2 MVP code.

## Causal frontier

A frontier is a version vector:

```kotlin
typealias SyncFrontier = Map<String, Long>
```

Each entry means: "this state has observed all batches from this replica through this sequence."

A batch has:

```kotlin
@Serializable
data class SyncMutationBatch(
    val revision: SyncRevision,        // ownerDeviceId + owner sequence
    val observed: SyncFrontier,        // frontier before this local batch
    val generatedAtEpochMillis: Long,  // diagnostics only
    val mutations: List<SyncMutation>,
)
```

Rules:

- `revision.deviceId` must equal journal `ownerDeviceId`.
- `revision.sequence` must be strictly greater than the prior local batch.
- `observed[ownerDeviceId]` must be lower than `revision.sequence`.
- timestamps never establish causal order and never choose a winner.
- batch A happens-before batch B when B's observed frontier contains A's replica sequence or a later one.
- batches are concurrent when neither causally observes the other.

## Mutation model

The journal records user-state deltas, not full-document replacements.

Mutations are expressed at record/property granularity so independent edits can merge without a common three-way base:

```text
SetField(recordId, propertyPath, JSON value, recordUpdatedAtEpochMillis)
RemoveField(recordId, propertyPath, recordUpdatedAtEpochMillis)
DeleteRecord(recordId, recordUpdatedAtEpochMillis, deletedAtEpochMillis)
```

Arrays and primitive values are atomic field values. JSON objects may be diffed recursively to leaf property paths.

Every mutation carries the source record's `updatedAtEpochMillis`; deletes also carry `deletedAtEpochMillis`. These timestamps reconstruct adapter-visible record metadata only. They never establish causal order and never choose a winner. When independent concurrent field mutations contribute to one active materialized record, its diagnostic `updatedAtEpochMillis` is the maximum contributing record timestamp after the causal winner set has already been determined.

A newly created record is represented by `SetField` mutations from a missing record. A local record removal is represented by `DeleteRecord`.

Adapters remain responsible for domain import/export. A sync cycle computes the local mutation batch by diffing:

```text
last conflict-free accepted materialized document
vs
current locally exported document
```

The existing outbox still coalesces "this logical document is dirty"; it does not become an operation store.

## Deterministic materialization

For each logical document kind:

1. list and validate every v2 replica journal;
2. resolve one canonical genesis: empty state when every journal has null genesis, or the one identical non-null legacy genesis copied across the participating journals;
3. take the union of all unique mutation batches, keyed by `SyncRevision(deviceId, sequence)`;
4. verify each journal's internal sequence and observed-frontier consistency;
5. deterministically reduce mutations from genesis using causal order;
6. produce:
   - a materialized `SyncDocumentEnvelope`;
   - the resulting causal frontier;
   - zero or more explicit conflicts.

### Property mutation rule

For one `recordId + propertyPath`:

- if mutation B causally observes mutation A, B supersedes A;
- if A and B are concurrent and produce the same value/removal, collapse them as equivalent;
- if A and B are concurrent and produce different values, persist a `FIELD_DIVERGENCE` conflict;
- when three or more distinct concurrent values exist, emit a deterministic set of pairwise conflict entries against the canonically first contender so every competing value is represented; canonical ordering is presentation/deduplication only and never selects a winner;
- no timestamp, device ID lexical order, or arbitrary iteration order may select a value for concurrent disagreement.

Independent property paths merge automatically.

### Delete rule

For one record:

- a `DeleteRecord` causally after all active field mutations produces a tombstone;
- a field mutation causally after the delete represents an intentional later recreation/edit and becomes active;
- delete concurrent with an active field mutation produces `DELETE_EDIT`;
- concurrent equivalent deletes collapse.

The conflict ledger remains the user-visible/durable record of unresolved divergence.

## Local changes and publication

A sync cycle remains pull-first.

For each logical document:

1. list/download all replica journals for that kind;
2. materialize the remote causal state;
3. load the last accepted local materialized state/frontier;
4. export current local domain state;
5. derive a local mutation batch from accepted state -> current local state when the outbox is dirty;
6. give that batch the accepted/observed frontier;
7. materialize remote journals plus the candidate local batch;
8. append the candidate batch to the local owned journal;
9. write only the local owned journal;
10. if materialization has no conflict, apply needed remote changes locally and persist the new accepted state/frontier;
11. if materialization has a conflict, persist the conflict ledger and keep local operational state intact.

Publishing a conflict-causing local batch is permitted because it is append-only logical evidence and cannot overwrite another replica. The sync engine must still never silently choose a winner for the materialized accepted state.

A failed network write keeps the outbox dirty. Sequence allocation is persisted before the remote write; gaps are allowed.

If a write outcome is ambiguous, the next pull first inspects the current device's owned journal for batches beyond the last accepted frontier. Those already-published batches are treated as the device's causal published state, not emitted again. The engine materializes history only through the newest such local batch's observed frontier plus that batch to reconstruct the local causal base, diffs that base against current local domain state, and creates a new batch only for genuinely newer local changes. This prevents duplicate publication without falsely marking unseen remote batches as observed.

## Why SYNC-P2 is eliminated

Cross-device PATCH contention disappears from the correctness model.

Device A only writes A-owned journals. Device B only writes B-owned journals. A never overwrites B's bytes.

The existing remote-version check may remain as defense-in-depth for the local owned file. A version mismatch on a file that should have exactly one writer indicates an invariant violation or an ambiguous prior local write; it must trigger pull/reconciliation, not blind overwrite.

The absence of Drive CAS is therefore no longer a correctness hole.

## Why SYNC-P3 is eliminated

Concurrent bootstrap intentionally creates distinct files:

```text
A -> library shard owned by A
B -> library shard owned by B
```

They are not duplicate logical files; they are two replicas participating in one logical document.

Within one replica, pre-generated persisted Drive IDs make create retry idempotent. Duplicate filenames are irrelevant because ownership is keyed by protocol/kind/owner metadata and remote ID.

Two different physical files claiming the same v2 `(kind, ownerDeviceId)` are malformed remote state and fail closed. Protocol v2 itself must not create that condition.

## Manifest

The current shared mutable `manifest.json` has the same concurrency defect as the logical documents and must not remain a protocol-v2 correctness dependency.

For protocol v2:

- remote replica journals and their metadata are authoritative for sync discovery;
- the shared manifest is not written;
- manifest data may still be derived locally for diagnostics;
- legacy `manifest.json` may be read for migration diagnostics but must not control or block protocol-v2 materialization.

This is consistent with the existing rule that the manifest is derived and not domain-authoritative.

## Legacy protocol-v1 compatibility

Exact legacy filenames such as `library.json`, `collections.json`, and `source-mappings.json` are never mutated by a protocol-v2 client.

When exactly one valid legacy file exists for a logical kind and no v2 journal exists yet, its decoded content becomes the immutable `SyncReplicaGenesis`. The first v2 journal and every later journal created while that genesis is active copies the same legacy remote ID, revision token, and canonical base document. Local changes are encoded only as deltas from that genesis, so an unchanged legacy value on one device does not become a false concurrent edit against another device's real change.

If multiple legacy files exist for one logical kind, migration fails closed rather than inventing a winner.

Once any v2 journal exists, its embedded genesis is the migration authority. A currently visible legacy file with a different ID/revision/content than that embedded genesis is an unsafe mixed-protocol condition. The client must surface a typed protocol/migration failure and must not merge the changed legacy file through timestamp or filename heuristics. A new v2 client can reconstruct the migration base from the journals themselves; correctness does not depend on the legacy file remaining unchanged or even present forever.

Protocol v2 does not delete legacy files automatically during this audit fix. Safe garbage collection is a separate future concern.

## Failure handling

New/updated typed failure behavior must distinguish at least:

- malformed replica journal;
- duplicate owner shard;
- unsupported protocol version;
- unsafe legacy mutation after v2 activation;
- authorization required;
- network unavailable;
- remote unavailable/rate limited;
- local state unavailable.

Cancellation continues to propagate.

No protocol failure may roll back a successful local user action.

## Scope boundaries

### In scope

- protocol-v2 replica/journal models and codec;
- causal materializer/conflict detection;
- local replica metadata persistence + migration;
- Drive `generateIds`, appProperties, owned-file create/update/list/download support;
- v1 read-only bootstrap compatibility;
- orchestrator changes required to use replica journals;
- regression/adversarial tests for P2/P3;
- removal of shared manifest writes from the v2 correctness path;
- Fast CI and final Full Verify because the change crosses domain/data/Android networking.

### Out of scope

- Milestone 13;
- tracker reconciliation;
- UI redesign;
- backend service;
- cross-user sharing;
- journal compaction/garbage collection;
- automatic deletion of legacy v1 files;
- generic Mihon backup changes;
- Reader/downloader/source-network changes.

## Required adversarial acceptance cases

The implementation is not accepted without automated coverage for all of these cases:

1. two fresh devices bootstrap concurrently and both edits survive;
2. two devices edit different fields of the same record concurrently and the result merges;
3. two devices edit the same field differently and an explicit conflict is produced;
4. concurrent delete/edit produces `DELETE_EDIT`;
5. causally later edit supersedes an older value without conflict;
6. a stale replica journal is causally dominated and cannot resurrect old state;
7. three replicas with partially connected histories converge to the same materialized result independent of listing order;
8. retry after ambiguous create reuses the same reserved Drive ID and cannot create a second same-owner shard;
9. a device refuses to update a foreign-owned shard;
10. duplicate v2 shards for the same owner fail closed;
11. one valid legacy v1 document imports deterministically;
12. duplicate legacy documents fail closed;
13. legacy mutation after v2 activation fails closed as mixed protocol;
14. network failure leaves local state and outbox intact;
15. cancellation is propagated;
16. tokens/content are not emitted through HTTP logging;
17. existing Library/Source Mappings/Collections/Chapter Overrides adapter round trips continue to pass.

## Rollout and verification

Implementation must follow TDD and small commits.

Normal task gate: Fast CI:

```text
spotlessCheck
:app:compileDebugKotlin
testDebugUnitTest
verifySqlDelightMigration
```

A final `[full-ci]` checkpoint is required because this changes domain protocol, SQLDelight state, Android Drive transport, and orchestration together.

No APK is required solely for the protocol change. The already-pending human MVP-V3 device test remains the physical acceptance gate after automated verification.

## Security and privacy

- Only `drive.appdata` remains authorized.
- Replica metadata contains only protocol/kind/device identity; no Google account token or tracker credential.
- Access tokens stay transient.
- Drive request/response bodies containing private sync state must not pass through HTTP logging interceptors.
- Device ID is a random replica identifier, not an account identity and not canonical domain identity.

## Consequences

Benefits:

- removes shared-writer races without requiring a custom backend or undocumented Drive behavior;
- makes bootstrap concurrency a normal replication case instead of corruption;
- preserves explicit conflicts and local-first behavior;
- retains a small physical file count: approximately one journal per device per logical document kind;
- retries become idempotent at both file creation and mutation-batch identity levels.

Costs:

- protocol v2 is materially more complex than shared snapshots;
- journals grow over time because compaction is intentionally deferred until a safe causal-GC protocol exists;
- sync state must track a causal frontier;
- old clients that continue mutating protocol-v1 shared files cannot safely coexist indefinitely with v2.

These costs are accepted because silent data loss is not an acceptable foundation for Tsuzuki sync.
