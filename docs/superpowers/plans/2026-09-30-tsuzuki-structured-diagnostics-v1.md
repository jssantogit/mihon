# Tsuzuki structured diagnostics v1

Date: 2026-09-30
Branch: `tsuzuki/structured-diagnostics-v1`
Base: `main` at branch creation
Status: authorized implementation branch

## Goal

Make exported diagnostics capable of explaining a real Tsuzuki operation without replacing Mihon's existing Logcat/crash pipeline.

The first delivery is intentionally narrow:

1. harden privacy in verbose HTTP logging;
2. add a small provider-neutral structured diagnostics contract;
3. keep a bounded local diagnostic history;
4. include that history in the existing exported crash log;
5. instrument one complete flow: `ResolveReadingSource`.

Do not redesign unrelated logging, do not add remote telemetry, and do not change resolver semantics.

## Verified current gaps

- `CrashLogUtil.dumpLogs()` exports Logcat at `E` unless verbose logging is enabled, so WARN/INFO context is absent from normal reports.
- `CanonicalLibraryScreenModel` already records meaningful failures at WARN.
- `ResolveReadingSource` deliberately continues after several search/confirmation/listing failures and can end in `NotFound` with no explanation of whether sources were empty or failed.
- Catalog screen failures often become UI state without an operation trace.
- `KitsuHttpClient` classifies errors but does not correlate them with an operation or duration.
- `HttpPageLoader.internalLoadPage` converts failures into `Page.State.Error` without recording the failing stage.
- verbose OkHttp logging uses `HEADERS` without explicit sensitive-header redaction.
- Logcat is installed after graph injection. Do not move this in v1 unless a proven safe bootstrap design is necessary.

## Architectural rules

- Keep existing Mihon Logcat calls and crash handling.
- No new Gradle module.
- Domain contract must not depend on Android, Logcat, Firebase, file APIs, or OkHttp.
- Implementation may live in `data`/`app` using existing DI.
- No remote telemetry and no automatic uploads.
- Diagnostic failure must never break reading, resolving, downloads, or startup.
- Preserve cancellation semantics. Never swallow `CancellationException`.
- Never log raw credentials, cookies, auth headers, tokens, manga titles, search text, full URLs, source URLs, page image URLs, or user-entered server URLs.
- Prefer stable logical endpoint/event names and pseudonymous IDs.
- Keep canonical title identity, provider identity, and Mihon/source identity as separate fields.
- Incognito mode must disable content-associated persistent diagnostic history.
- Avoid provider-specific diagnostic architecture; provider/source are attributes, not event classes.

## V1 event model

Use a small stable event structure, for example:

- timestamp
- severity
- subsystem
- event name
- session id
- operation id
- stage
- outcome
- durationMs when applicable
- attempt when applicable
- sanitized attributes
- sanitized error category/code

Exact Kotlin shape is implementation-defined, but it must be versionable and unit-testable.

Suggested stable resolver events:

- `source.resolve.started`
- `source.resolve.mapping_reused`
- `source.resolve.preferred_sources`
- `source.search.started`
- `source.search.completed`
- `source.search.failed`
- `source.match.evaluated`
- `source.mapping.confirmation_failed`
- `source.resolve.completed`

Final outcomes must distinguish at least:

- resolved
- reused
- needs_confirmation
- not_found_no_candidates
- not_found_with_source_failures
- no_preferred_sources
- cancelled

Do not change the public `SourceResolutionResult` semantics merely to expose these diagnostic distinctions.

## Privacy hardening

Before relying on verbose logging, redact at minimum any headers supported by the installed OkHttp logging API equivalent to:

- Authorization
- Proxy-Authorization
- Cookie
- Set-Cookie

Verify the actual dependency API before implementing.

Structured diagnostics must use an allowlist/redactor before both Logcat output and persistent output. Tests must prove known secret/token/cookie strings never survive sanitization.

## Local history

Implement a best-effort bounded local history suitable for export after process restarts.

Preferred initial constraints:

- JSONL or an equally streamable versioned format;
- under `noBackupFilesDir` or another non-backed-up private location;
- bounded writer queue;
- single writer per process;
- approximately 5 MiB total cap;
- maximum 3-day retention;
- oldest-first eviction/rotation;
- record a dropped-event count when saturation occurs.

These values are initial engineering limits, not user-facing product guarantees.

## Export integration

Keep the existing Advanced settings export UX.

Enhance `CrashLogUtil` so the exported report contains:

1. current debug/app/device info;
2. extension info;
3. explicit crash exception when supplied;
4. recent structured diagnostic history;
5. Logcat as a supplemental section.

For the Logcat subprocess:

- impose a timeout;
- inspect the exit code;
- mark the report when Logcat collection is partial/unavailable;
- do not fail the whole export if Logcat fails.

Do not require verbose mode for structured WARN/ERROR context to appear in the export.

A ZIP/bundle is not required for v1; preserving the current single-share flow is preferred unless implementation constraints prove otherwise.

## Resolver instrumentation acceptance criteria

Given a resolver operation, the exported diagnostics must let us determine:

- whether an existing mapping was reused;
- requested language and broaden flag without exposing title/search text;
- how many preferred/target sources were considered;
- each source ID attempted;
- whether each search returned candidates, returned a typed failure, threw, or was cancelled;
- candidate count per source;
- best confidence bucket/value if safe;
- whether auto-confirmation was attempted;
- whether confirmation failed;
- final resolver outcome;
- total duration.

If every source fails and the current behavior returns `NotFound`, diagnostics must explicitly show that source failures occurred.

## Tests

TDD required.

At minimum cover:

- sanitizer removes credentials/cookies/tokens and raw URLs/search terms;
- persistent history rotation/retention and queue overflow behavior;
- writer/storage failure is non-fatal;
- incognito persistence behavior;
- export succeeds with structured history when Logcat command fails/times out;
- resolver successful reuse path;
- resolver search success path;
- resolver all-sources-failed path;
- resolver no-candidate path;
- resolver confirmation failure path;
- cancellation propagation;
- operation ID is stable across all events from one resolver execution and distinct across executions.

Use repository CI/GitHub Actions. Do not run local Gradle.

## Out of scope for the first PR

Do not expand the first delivery into all subsystems.

After v1 is proven, follow-up waves may instrument:

- catalog/discovery and Kitsu/provider HTTP;
- canonical library/materialization/migrations;
- Collections planner/execution/cache;
- reader page loading;
- downloads;
- cover/artwork resolution.

The current cover investigation may continue using temporary focused `TsuzukiCover` logs independently. Do not make this branch depend on PR #53.

## Merge requirements

Before requesting merge:

- branch rebased/updated against current `main` if necessary;
- all CI gates green;
- no behavior changes to source matching/resolution;
- no raw secret/content leakage in tests or review;
- exported report physically tested from an APK;
- concise documentation of event schema and retention/privacy guarantees.
