# Tsuzuki MVP-V3 — Milestone 10: Google Authentication

**Status:** Approved for implementation  
**Branch:** `tsuzuki/mvp-v3-google-auth`  
**Baseline:** `b93458019694ac367601b701fc63f297fd25ec80`

## Goal

Provide an optional, local-first Google account authorization foundation that the future Drive Sync Engine can consume without coupling Library, Reader, Collections, or Tsuzuki domain identity to Google SDK details.

This milestone implements Google account connection and Drive authorization only. It does not implement Drive synchronization.

## Architectural decisions

- Use Google Identity `AuthorizationClient` for client-side authorization to Google Drive.
- Request only `https://www.googleapis.com/auth/drive.appdata`.
- Do not introduce a Tsuzuki backend.
- Do not persist access tokens, refresh tokens, passwords, or client secrets.
- Do not use tracker OAuth callbacks for Google authorization.
- Prefer Activity Result / PendingIntent integration over a new custom URI callback.
- Keep Google SDK classes behind an app/data adapter.
- Local Library, Reader, backup, and other offline behavior must remain available while signed out or while Google is failing.
- Disconnecting Google must never delete local Tsuzuki state.
- Sync remains separate from Backup.

## External configuration

Dev C Android OAuth identity:

- package: `app.mihon.tsuzuki.devc`
- signing certificate SHA-1: `9E:E1:58:3A:CF:ED:D4:24:9F:09:DB:23:7A:D5:27:0E:5F:13:24:82`

A Google Cloud test project must enable Drive API and configure an Android OAuth client for that exact package + signing certificate before physical login testing.

## Execution blocks

### 10.1 — Neutral contract and state machine

Create Google-account identity, auth state, failure/result models, and a pure state machine with unit tests.

Acceptance:
- initial signed-out state;
- connect success;
- cancellation restores previous stable state;
- authorization-required state;
- recoverable and fatal failures;
- disconnect resets auth state;
- no Google SDK dependency.

Gate: Fast CI.

### 10.2 — Platform boundary and fake

Create an app-side platform abstraction for authorization operations and a fake implementation for deterministic tests.

Acceptance:
- SDK behavior is not tested directly;
- success, interactive-required, cancellation, recoverable failure, and fatal failure can be simulated;
- no Drive API implementation.

Gate: Fast CI.

### 10.3 — Google AuthorizationClient adapter

Add the current official Google auth dependency and implement the real adapter with `drive.appdata`.

Constraints:
- no offline access;
- no server auth code;
- no refresh token persistence;
- no custom Google deep link;
- no changes to tracker OAuth callbacks.

Gate: Fast CI, then Full Verify because Gradle/Android integration changes.

### 10.4 — Restore and account hint

Persist only a non-secret account hint needed to reacquire authorization after restart. Revalidate the grant through Google rather than trusting a local signed-in boolean.

Acceptance:
- valid grant restores Connected;
- missing/revoked grant becomes AuthorizationRequired;
- account removal/failure leaves local app usable;
- no OAuth token is persisted.

Gate: Fast CI.

### 10.5 — Interactive authorization coordinator

Wire PendingIntent/Activity Result handling without exposing Android types to the domain contract.

Acceptance:
- explicit user connect;
- interactive consent when needed;
- cancellation leaves coherent state;
- recreation/result handling is safe.

Gate: Fast CI.

### 10.6 — Minimal Settings UI

Add a dedicated Google/cloud account surface separate from Backup.

States:
- disconnected;
- connecting;
- connected account;
- authorization required;
- recoverable failure;
- error.

Gate: Fast CI.

### 10.7 — Hardening

Cover concurrent requests, repeated taps, revoked grants, token rejection recovery, network/Play Services failures, and logging hygiene.

Gate: Fast CI.

### 10.8 — Milestone verification

Run the complete unit suite and integration verification. Request `[apk]` for `assembleDevc`.

Physical-device Human Gate:
- local app works without Google;
- connect succeeds;
- restart restores coherent account state;
- offline/failure does not block Library/Reader;
- cancellation is safe;
- disconnect revokes authorization but preserves local state;
- external revocation is detected;
- no other Tsuzuki APK intercepts the flow;
- no sensitive token appears in logs.

Milestone 10 is accepted only after CI and physical-device evidence are both green.

## Forbidden scope

Do not implement or modify:

- Drive Sync Engine;
- sync outbox or cloud documents;
- cloud merge/conflict rules;
- CanonicalChapter / ChapterVariant / Reader progress;
- Collections AST / Query Planner / Collection structures;
- Source Resolver semantics;
- tracker authority/reconciliation;
- Drive fallback progress;
- existing tracker OAuth callback behavior.
