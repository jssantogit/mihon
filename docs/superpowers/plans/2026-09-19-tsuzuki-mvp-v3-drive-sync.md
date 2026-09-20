# Tsuzuki MVP-V3 — Milestone 11 Drive Sync Engine

Date: 2026-09-19
Branch: `tsuzuki/mvp-v3-drive-sync`
Base: accepted Milestone 10 merge `7c1b9a102741dfc962d271641d058dd0a4803766`

## Goal

Implement optional, local-first synchronization of Tsuzuki-owned private state through Google Drive `appDataFolder` without turning Drive into the operational database and without weakening local Library/Reader behavior.

Milestone 12 tracker/Drive progress reconciliation remains out of scope.

## Fixed invariants

- Google remains optional.
- Local SQLDelight state is authoritative for immediate app behavior.
- Cloud failure never rolls back a successful local action.
- Sync is not backup.
- Only `drive.appdata` is used; broad Drive scopes are forbidden.
- Access tokens are transient and must never be persisted or logged.
- Tracker tokens remain device-local.
- Downloads, pages, caches and recomputable inventories are never synchronized.
- No blind last-write-wins for conflicting edits.
- Independent edits merge automatically.
- Deletions propagate through tombstones.
- Persisted cloud formats are versioned and migratable.
- Fallback progress authority is deferred to Milestone 12.

## Architecture

```text
local domain repositories
        |
        v
SyncDocumentAdapter
        |
        +----> mark document dirty
        |          |
        |          v
        |     sync outbox
        |
        v
current local document
        |
        v
three-way merge
(base / local / remote)
        |
        +---- conflict ledger when the same property diverges
        |
        v
DriveSyncTransport
        |
        v
Google Drive appDataFolder
```

The outbox does not store a second authoritative copy of domain state. It coalesces dirty logical documents. A separate sync-state record stores the last accepted base snapshot and remote revision needed for three-way merge.

## Logical documents

Initial document kinds:

- `manifest.json`
- `library.json`
- `collections.json`
- `settings.json`
- `source-mappings.json`
- `chapter-overrides.json`

`fallback-progress.json` is reserved by the specification but is not activated until Milestone 12.

Documents use stable record IDs and maps keyed by ID rather than positional arrays so field-level three-way merge is deterministic.

Each document carries:

- protocol/schema version;
- document kind;
- document revision metadata;
- records keyed by stable ID;
- per-record `updatedAt`;
- optional `deletedAt` tombstone;
- record revision metadata.

Timestamps are evidence/diagnostics, not automatic conflict winners.

## Three-way merge rule

Given last synchronized `base`, current `local`, and current `remote`:

1. unchanged on one side + changed on the other -> take the changed side;
2. same change on both sides -> accept once;
3. changes to different properties of the same record -> merge;
4. same property changed to different values -> explicit conflict;
5. delete vs unchanged -> tombstone wins;
6. delete vs concurrent edit -> explicit conflict;
7. conflicts never silently choose local or remote.

## Local persistence

Planned SQLDelight ownership:

### `tsuzuki_sync_outbox`

Coalesced pending work per logical document:
- document kind;
- enqueued timestamp;
- attempt count / retry metadata.

### `tsuzuki_sync_state`

Last accepted synchronization base:
- document kind;
- Drive file ID when known;
- remote revision token/version;
- canonical base JSON;
- last successful sync timestamp.

### `tsuzuki_sync_conflicts`

Explicit unresolved conflicts:
- document kind;
- record ID;
- property path;
- base/local/remote values;
- created timestamp.

Local domain tables remain the operational source of truth.

## Drive transport

Use the Drive REST API through the existing HTTP stack instead of introducing a large Drive client dependency.

The transport owns:
- list/search only within `spaces=appDataFolder`;
- create with `parents=["appDataFolder"]`;
- download media;
- create/update JSON content;
- remote metadata/revision extraction;
- typed Drive failures.

The transport never owns merge behavior.

Credential rejection from an authoritative Drive request must call the existing `GoogleAuthorizedAccess.invalidateRejectedAccessToken()` path and transition Google auth to reauthorization without touching local domain data.

Do not classify every HTTP 403 as revoked credentials; quota, policy and permission errors remain distinct failures.

## Execution blocks

### 11.1 — Sync protocol core

Create transport-neutral models for:
- document kind;
- document envelope;
- record envelope/tombstone;
- remote revision;
- typed sync failures;
- deterministic canonical representation constraints.

Add pure tests for invariants and redaction.

Acceptance: Fast CI green.

### 11.2 — Three-way merge + conflict model

Implement pure merge engine with property-level conflict detection, tombstone semantics and deterministic results.

Acceptance: exhaustive unit tests for independent edits, identical edits, same-field conflicts, delete/edit conflicts and malformed/version-mismatched documents.

### 11.3 — Local sync state + outbox

Add SQLDelight tables/repositories for:
- coalesced dirty documents;
- last synchronized base snapshots;
- unresolved conflicts.

No domain write may depend on cloud success.

Acceptance: migration verification + repository tests.

### 11.4 — Drive appDataFolder transport

Implement authenticated REST transport using `GoogleAuthorizedAccess` and existing networking.

Capabilities:
- list logical files in appDataFolder;
- download;
- create;
- update;
- typed HTTP/network/auth failures;
- no token logging.

Authoritative invalid-credential responses invalidate the transient Google session.

Acceptance: fake HTTP tests + Fast CI; request Full Verify because Android/auth/network integration changes.

### 11.5 — Sync cycle orchestrator

Implement pull/merge/apply/push cycle with:
- single-flight mutex;
- cancellation safety;
- local-first behavior;
- retryable failure classification;
- no rollback of local state;
- base snapshot update only after accepted remote state.

### 11.6 — Available document adapters

Wire adapters only for domain state already present in the integrated base:
- canonical Library;
- logical source preferences/mappings;
- explicitly approved roaming settings.

Do not invent adapters for parallel-lane schemas that are not yet integrated.

### 11.7 — Collections/chapter override adapters

After the authoritative Collection / Canonical Chapter schemas are integrated, add adapters without redefining their contracts.

If those lanes are not yet integrated, stop this block rather than duplicating their domain models.

### 11.8 — Scheduling, diagnostics and auth-revoke hardening

Add conservative background/manual triggering, local status/diagnostics, bounded retry behavior and authoritative external-revoke recovery through Drive response evidence.

No sync operation may block normal local use.

### 11.9 — Final verification

- Fast CI;
- Full Verify;
- signed Dev C APK;
- physical test with two logical sync directions;
- offline mutation then reconnect;
- independent merge;
- explicit conflict;
- in-app disconnect;
- external Google grant revoke followed by Drive request -> reauthorization;
- local data preserved throughout.

## Scope exclusions

Milestone 11 does not implement:
- tracker/Drive progress arbitration;
- tracker credential synchronization;
- Reader/downloader rewrites;
- broad Drive file access;
- manual backup/export replacement;
- social/public backend;
- unrelated cleanup.

## CI strategy

Fast CI after each meaningful implementation block.

Use `[full-ci]` only at the Drive transport integration checkpoint and final milestone checkpoint.

Use `[apk]` only once a runtime Drive flow is ready for physical validation.


## Final implementation checkpoint

Code checkpoint before physical validation:

- Fast CI `35477501573`: Format, Kotlin Compile, Unit Tests, and SQLDelight Migrations all green.
- 11.3 local SQL sync state/outbox is implemented.
- 11.7 Collections and durable `chapter-overrides.json` adapters are implemented against the integrated authoritative schemas.
- `tsuzuki_chapter_overrides` is durable, portable across devices, tombstone-capable, and migration-backed by migration 24.
- Chapter override outbox triggers use explicit UPSERT coalescing so repeated domain writes do not violate the logical-document primary key.
- Sync document application follows dependency order so title materialization precedes title-dependent chapter preferences.
- WorkManager scheduling, manual sync diagnostics/UI, retry/backoff, manifest, Drive appDataFolder transport, and authorization hardening are wired.
- Tracker/Drive progress arbitration remains excluded for Milestone 12.

This checkpoint requests final Release Compile and signed Dev C APK generation. Physical Drive validation remains the final Milestone 11 gate.


## Final implementation checkpoint — 11.9

Code checkpoint validated by Fast CI before final artifact gates:

- Drive appDataFolder transport and three-way merge engine wired into runtime;
- durable sync state/outbox/conflict persistence;
- Library, source mappings/preferences, Collections and chapter overrides adapters;
- `tsuzuki_chapter_overrides` durable schema + migration 24;
- portable override identity (no device-local mapping/variant IDs in synced records);
- dependency-aware document application order;
- WorkManager background sync + manual sync diagnostics UI;
- external authorization failure remains explicit and recoverable;
- chapter override outbox triggers use guarded `WHERE NOT EXISTS` inserts so outer SQLite UPSERT conflict policies cannot reintroduce duplicate-key failures.

Fast CI gate immediately before this checkpoint: `35478254749` — Format, Kotlin Compile, Unit Tests and SQLDelight Migrations all green.

This documentation-only commit intentionally triggers the final Release Compile and signed Dev C APK gates without changing validated runtime code.
