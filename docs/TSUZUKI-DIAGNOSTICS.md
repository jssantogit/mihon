# Tsuzuki diagnostics v2

Tsuzuki records a small, local structured trace for supported operations. It augments Mihon's existing Logcat and crash handling; it does not upload diagnostics or replace the reading and crash pipeline.

## Structured event schema

The JSONL schema is version `2`. Each event record requires `schemaVersion`, `recordType`, `timestampMillis`, `severity`, `subsystem`, `name`, `sessionId`, `stage`, `outcome`, and `attributes`; `operationId`, `durationMillis`, and `attempt` are optional. `severity` and `subsystem` use uppercase enum names. Event name, stage, and outcome use lowercase underscore form, such as `source_resolve_started`, `preferred_sources`, and `not_found_with_source_failures`. Safe-code attributes retain their uppercase enum code names. Overflow records use `recordType: "dropped_events"` and a count.

The current event names cover source resolution: start, mapping reuse, preferred-source selection, search start/completion/failure, match evaluation, mapping-confirmation failure, and resolution completion. Outcomes distinguish successful resolution, reuse, confirmation, empty results, source failures, typed failures, thrown failures, and cancellation. The resolver's user-facing result semantics remain unchanged.

Production source resolution requires the real `StructuredDiagnosticRecorder` through dependency injection; the no-op recorder is test-only fallback behavior and must not be a default on an injected production constructor.

Attributes are allowlisted: numeric source IDs and bounded counts, booleans for broaden/reuse/auto-confirm/ambiguity, language tags, pseudonymous canonical/Mihon references, confidence values/buckets, fixed error categories, and HTTP status. Typed reading-source failures are mapped to those fixed categories, with bounded HTTP status preserved when available. The sanitizer rejects an invalid schema, UUID, timestamp, duration, or attempt and drops unknown or invalid attributes. It accepts no exception text or arbitrary message. The same sanitized JSON encoding is used for structured Logcat lines and persisted history.

Each process gets a random UUID session ID and a private random salt. Canonical-title and Mihon references use separate salted SHA-256 inputs; they are stable within that recorder process and change after restart. They do not expose the original IDs and are not intended to correlate records across app launches.

## Storage, loss, and incognito

Structured history is stored in the app's private `noBackupFilesDir`. The writer is best effort, uses a bounded queue, and retains at most 5 MiB for up to 3 days. Old records are evicted first. Queue saturation is counted when possible. A crash, full queue, storage error, or bounded export flush can lose recent events; diagnostics must never block or fail the operation being observed.

Incognito mode disables persistent structured history. If reading the preference fails, persistence is disabled. Incognito does not suppress the sanitized Logcat line, which follows Android's normal Logcat retention behavior. The export omits stored structured history while incognito is enabled.

## HTTP and export privacy

Verbose HTTP logging emits only recognized request methods, response status/duration, a fixed allowlist of header names with all values redacted, and bounded body byte counts. URLs, body contents, unrecognized headers, and failure details are suppressed.

The existing Advanced settings export remains one shareable text file. It includes app/device debug information, problematic extension information, an explicit exception when supplied, structured history, and supplemental Logcat. Logcat capture has a 10-second timeout, a 4,000-line request limit, and a 512 KiB output cap. Timeout, nonzero exit, start failure, stuck process, truncation, and incomplete reads are marked; partial output is retained where available. A Logcat failure does not prevent report composition.

The structured section and HTTP logger have the guarantees above. The legacy explicit exception remains verbatim, and supplemental Mihon Logcat can still contain third-party free text or other sensitive content. The export is not wholly sanitized. Review the report before sharing it.

## Physical export smoke

Before merge, validate on a physical device with the tested APK and record its commit SHA, device/Android version, steps, and result:

1. Record a resolver operation and export from the existing Advanced settings flow; verify debug/device data, extensions when applicable, structured history, and supplemental Logcat appear in one shareable text file.
2. Exercise an unavailable Logcat command or equivalent test condition and confirm the report still opens with a fixed partial/unavailable marker.
3. Enable incognito, record an event, export, and confirm the structured history section contains no persisted event from that period.
4. Inspect the structured and verbose HTTP sections for absence of a known test title, URL, credential, cookie, or token. Treat the legacy exception and Logcat sections according to their separate privacy limits above.


## Diagnostics v2 — Wave 1

Wave 1 turns artwork and metadata debugging into correlated traces without widening the privacy surface.

- every structured event may carry a random `workflowId`, `operationId`, `parentOperationId`, and a closed `workflow` enum;
- canonical artwork resolution records observation count, selected provider, presence/absence of artwork, and total duration;
- provider metadata resolution records provider-safe results and duration without titles, URLs, response bodies, or exception text;
- the shared cover component exposes only candidate index/count, request-data presence, fallback, success, and failure signals;
- Continue Reading, canonical Detail, and unified Library emit the same safe image-load trace;
- invariant codes are closed enums; the first guard detects a resolved candidate list that loses request data before rendering;
- recorder health counters distinguish sanitizer rejection and sink failures from a genuinely empty diagnostic history;
- exports begin with a deterministic summary of workflows, failures, invariant violations, dropped events, slow operations, and recorder health;
- App tests scan critical production source for the exact optional/NoOp DI defaults that caused the post-PR #55 artwork/diagnostics wiring regression.

Wave 1 does not add free-form diagnostic messages and does not log manga titles, URLs, credentials, cookies, provider responses, or image request payloads.


## Diagnostics v2 — Wave 2

Wave 2 extends the same correlated, allowlisted diagnostics contract across the reading runtime.

- provider Library refreshes record provider-safe start/fetch/completion events, projected item counts, and duration;
- ContentBinding resolution records bounded Add-on identifiers, persisted-binding hits/misses, resolved binding counts, and duration;
- source-artwork recovery reports a closed invariant when persisted ContentBindings exist but none can be restored into a materialized reading candidate;
- chapter refresh records start, reconciled evidence count, completion, timeout/cancellation, and duration;
- canonical Reader preparation records download-cache hit/miss, selected Add-on, selection-required counts, ready/unavailable/failure outcomes, and duration;
- the actual Reader chapter loader records page-ready count and load duration, including cancellation and failure;
- Reader progress emits bounded checkpoints instead of one event per page, plus completion/history timing;
- canonical downloads record cache reuse, content-option selection requirements, completion/failure, and duration.

Wave 2 deliberately records operational counts and closed identifiers only. Chapter labels, manga titles, source URLs, page URLs, local file paths, provider response content, and exception messages remain outside the structured event schema.

## Diagnostics v2 — Wave 3

Wave 3 adds an explicit reproduction workflow around the structured diagnostics from Waves 1 and 2.

- Advanced settings can start or stop a user-controlled detailed capture. A capture lasts at most 15 minutes by default and expires automatically; its small control state survives process recreation.
- while a detailed capture is active, Reader progress diagnostics can record each progress checkpoint instead of the normal sampled cadence;
- the structured-history section of the next export is scoped to the active or most recently completed capture window. Supplemental Logcat remains independently bounded by line/byte limits and is not time-filtered by the capture window;
- exports include a safe local runtime snapshot with counts for installed/enabled Add-ons, registered integrations, connected account providers, canonical titles, and external Library memberships, plus existing verbose/incognito/extension state;
- the recorder retains only the most recent sanitized structured event as process-local crash context, exposing closed subsystem/event/stage/outcome values and short workflow/operation references;
- the human-readable summary groups events by correlated workflow, reports per-workflow event/operation/failure counts and maximum observed duration, and keeps the global failure/invariant/slow-operation summary from Wave 1;
- a completed capture remains the structured export scope until a newer capture starts, allowing the user to stop reproduction first and export immediately afterward.

The capture feature does not enable remote telemetry and does not relax the structured-event allowlist. It does not persist raw titles, queries, URLs, page content, local file paths, credentials, cookies, tokens, provider bodies, or arbitrary exception text.

### Wave 3 validation checkpoint

The Wave 3 implementation is complete on the stacked diagnostics branch. The final validation gate is a full CI v2.1 run on the complete Wave 1 → Wave 2 → Wave 3 tree. No APK is requested by this checkpoint.
