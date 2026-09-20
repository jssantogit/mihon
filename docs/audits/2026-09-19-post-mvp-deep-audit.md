# Tsuzuki Post-MVP Deep Audit Ledger

Date: 2026-09-20  
Audit branch: `tsuzuki/post-mvp-deep-audit`  
Frozen baseline: `7c09c17c35f352a2aa3021911636b51e00afeea8`  
Last code HEAD before this ledger: `ef82c5fefc70e38cf47be87d6f06ca431f4f36b4`

## Status

The sync/Drive correction wave is complete and has passed Fast CI.

The original repository-wide audit mission was **not completed across every required A–J area** because the external Codex/Orchestra execution stopped repeatedly. This ledger therefore does not claim that the full foundation has received the planned repository-wide audit.

Milestone 13 has not started.

## Coverage completed

### Sync / Drive / merge / outbox

Deep adversarial coverage completed for the confirmed Drive concurrency findings and the protocol-v2 replacement:

- shared mutable Drive file overwrite race;
- concurrent bootstrap from the same empty listing;
- duplicate owner shards;
- idempotent generated-ID create;
- ambiguous successful create followed by 409/adoption;
- foreign-owner write rejection;
- causal frontiers and missing-history validation;
- concurrent independent field edits;
- same-field divergence;
- delete/edit conflict;
- tombstone causality;
- partially connected three-replica history;
- legacy v1 bootstrap;
- legacy v1 immutable genesis;
- stale v1 device migration after v2 already exists;
- outbox retention on failures;
- accepted-base retention on failed local-shard update;
- cancellation propagation;
- no shared manifest write in protocol v2.

### Persistence / migrations related to sync

Covered:

- migration 25 regression for repeated UPSERT/outbox interaction;
- migration 26 for accepted causal frontier and per-owner replica state;
- existing-row compatibility;
- replica ownership keyed by `(document_kind, owner_device_id)`;
- reserved Drive ID persistence;
- monotonic local replica sequence persistence;
- SQLDelight migration verification.

### Google Drive transport / privacy related to sync

Covered:

- `drive.appdata` transport path;
- replica `appProperties`;
- generated Drive IDs;
- single-writer owner validation;
- 409/412 classification as remote change;
- HTTP logging interceptor removal;
- transient authorization-token behavior retained.

## Confirmed findings

### SYNC-P1 / P1 — repeated UPSERT could fail with an existing outbox row

**Invariant violated:** successful local domain UPSERTs must not fail because the sync outbox already contains the logical document.

**Evidence:** SQLite conflict policy propagated through triggers and could reject repeated UPSERTs while a dirty outbox entry already existed.

**Fix:** `132c92a9a57348ddb19dc68bfdcf276e91d037ac` plus migration 25 and regression coverage for Library, Source Mappings, Collections, and Titles.

**Disposition:** FIXED.

---

### SYNC-P2 / P1 — shared Drive update allowed silent concurrent overwrite

**Invariant violated:** no silent last-write-wins for concurrent user state.

**Original execution path:**

1. device A reads shared logical file version N;
2. device B changes the same physical file;
3. device A PATCHes stale merged bytes after its preflight read;
4. Drive v3 does not provide the content CAS required by the old protocol;
5. B's change could be overwritten without the sync engine recording a conflict.

**Correction:** protocol v2 single-writer causal replica journals.

Key implementation evidence:

- causal journal core and validation;
- persisted accepted frontiers and replica ownership;
- single-writer Drive transport in `2baa7fd56a0d1ece9826fc9d2ae1c43b257b4f27`;
- v2 orchestration in `cd6fbf6155136d40b852cd5c37849e19381d2fa3`;
- runtime wiring and adversarial regression coverage through `b1642c10d75e5ae06721055484b483fa688074d8`.

Each device writes only the physical journal whose owner metadata matches its local device ID. Foreign-owner writes fail before PATCH. Concurrent logical changes are reduced causally and either merge or produce explicit conflicts.

**Disposition:** FIXED.

---

### SYNC-P3 / P1 — concurrent bootstrap could create duplicate shared logical files

**Invariant violated:** concurrent first sync must not produce a malformed remote state or permanently block later cycles.

**Original execution path:**

1. devices A and B both list an empty appDataFolder;
2. both create the same logical shared filename;
3. Drive permits duplicate names;
4. the next cycle sees multiple files for one logical document and fails closed.

**Correction:** protocol v2 per-device shards plus persisted generated Drive IDs.

Evidence includes:

- two devices are tested against the same empty listing snapshot and produce distinct valid owner shards;
- same-owner duplicate shards still fail closed;
- create uses a pre-generated persisted Drive ID;
- ambiguous successful create is adopted after 409 using the same ID rather than generating another file;
- convergence after bootstrap is covered.

Primary transport/orchestration commits: `2baa7fd56a0d1ece9826fc9d2ae1c43b257b4f27`, `cd6fbf6155136d40b852cd5c37849e19381d2fa3`, hardened by `b1642c10d75e5ae06721055484b483fa688074d8`.

**Disposition:** FIXED.

---

### SYNC-P4 / P1 — stale v1 device migration could lose same-field conflict causality

**Invariant violated:** v1-to-v2 migration must not silently choose a newer or older field value when the device has a persisted causal base proving the edits diverged.

**Evidence:** focused regression `f73c7154890c8877253a0b24d081518632cc3b92` demonstrated a second device still on v1 with accepted base `Base` and local edit `Local`, while the legacy remote had advanced to `Remote` and another device had already established v2. The initial v2 migration path skipped the v1 three-way base once any v2 journal existed, allowing the stale local edit to be encoded without the required conflict.

**Fix:** `ad71249a403ff90ec43ba6627799db12d07f48ae`.

The first migration of a device with an empty accepted v2 frontier now preserves its stored v1 causal base even when another device's v2 journal already exists. Same-field divergence becomes an explicit conflict and the second device does not publish a misleading shard.

**Disposition:** FIXED.

## Dismissed / challenged observations

### Remaining GET -> PATCH preflight on a local-owned shard

The Drive transport still checks remote revision before PATCH, but protocol-v2 correctness no longer depends on that check as a cross-device CAS.

Cross-device overwrite was challenged through owner-enforcement tests and content-preservation tests. A device cannot PATCH another owner's journal. The process-level sync mutex prevents overlapping local cycles, and ambiguous prior local writes are reconciled from the pulled owned journal before a new mutation batch is emitted.

**Disposition:** DISMISSED as the original P2 data-loss path; retained as defense-in-depth for local-owner invariant violations/ambiguous state.

### Multiple physical files after concurrent bootstrap

Under protocol v2, one file per owner is intentional replication rather than duplicate logical corruption. Only multiple v2 files claiming the same `(logicalKind, ownerDeviceId)` are malformed.

**Disposition:** DISMISSED as corruption when owners differ; duplicate same-owner remains a hard failure and is tested.

## CI evidence

### Fast CI

Latest accepted code checkpoint:

- HEAD: `ef82c5fefc70e38cf47be87d6f06ca431f4f36b4`
- Run: `35512565527`
- Format: green
- Kotlin Compile: green
- Unit Tests: green
- SQLDelight Migrations: green

Earlier relevant accepted checkpoints include:

- causal core: `35507867223` — 4/4 green;
- causal persistence/migration 26: `35508505362` — 4/4 green;
- single-writer Drive transport: `35509591223` — 4/4 green;
- integrated v2 migration invariants: `35511929028` — 4/4 green.

### Full Verify

Pending at ledger creation. This ledger commit carries `[full-ci]` to request the required release verification.

## Residual risks

- Replica journals are append-only at this MVP stage. Safe causal compaction/garbage collection is intentionally deferred; long-lived accounts can accumulate journal history.
- Old protocol-v1 clients must not continue indefinitely mutating the shared legacy file after v2 migration. A changed legacy file conflicting with embedded v2 genesis fails closed instead of being silently merged.
- Physical Google Drive/device behavior still requires the existing MVP-V3 human acceptance gate.
- Tracker/local/Drive reconciliation remains Milestone 12 work where not already covered by the current MVP-V3 scope.

## Audit coverage not completed

Because the external overnight auditor stopped, the following repository-wide passes from the original plan are **not claimed complete** by this ledger:

- full architecture/ownership audit outside the sync areas touched above;
- full Canonical Library / Source Resolver / Reader/progress adversarial pass;
- full Collections / Query Engine adversarial pass;
- repository-wide coroutine/lifecycle audit outside sync runtime;
- full release/build-variant audit beyond the required Full Verify;
- complete upstream Mihon compatibility audit;
- complete repository-wide test-quality audit.

These should not be silently marked as audited.

## Human gates remaining

- final physical MVP-V3 acceptance on device;
- verify Google connection/sync behavior with the integrated protocol-v2 build;
- normal integrated app smoke flows required before declaring the foundational phase closed.

## Next

If Full Verify succeeds, the sync P1 correction wave is automated-gate complete and is ready for the pending human MVP-V3 acceptance.

Do not merge automatically.  
Do not start Milestone 13 before the agreed foundation/integration gate.
