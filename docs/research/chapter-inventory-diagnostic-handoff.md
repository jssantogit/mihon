# Chapter inventory diagnostic handoff

## Purpose and scope

This temporary, opt-in diagnostic is intended to locate the first boundary where One-Punch Man
chapters disappear. It observes the extension inventory, probe conversion, canonical reconciliation
and persistence, and detail-screen list. It does not add a chapter fallback or change source
selection, canonical identity, reading progress, or chapter reconciliation decisions.

The report proves only what the installed Mihon extension returned and what Tsuzuki processed. A
listed chapter is not proof that the chapter pages can be opened or that the source has permission to
serve them.

## Android reproduction

Use a diagnostic build that includes this change; no APK was generated as part of this work.

1. Open One-Punch Man's Tsuzuki detail screen and let its current chapter list finish loading.
2. Scroll to **Temporary chapter diagnostics** and tap **Start diagnostic**. This starts a fresh
   in-memory session and records a snapshot of the list currently shown.
3. Tap **Refresh** in the top app bar. Wait for the refresh to finish; do not navigate to another
   title while capture is active.
4. Tap **Copy report** and paste the clipboard text into the approved issue or test record. The
   report is sanitized before it is copied.
5. Tap **Stop diagnostic** to stop collection while retaining the report. Tap **Clear** to erase
   the report and stop collection. Clearing is the recommended final step after the report has
   been safely captured.

Diagnostics are off by default. They are held only in process memory; leaving the app or process
termination loses the report. The collector retains at most 200 events and caps exported text at
32 KiB UTF-8. No database migration or persistent diagnostic store is introduced.

## Reading the report

The report header contains a session ID and a short stable hash of the canonical title ID. The title
ID itself is not included. Event lines are ordered by observation time:

| Stage | Meaning |
| --- | --- |
| `INVENTORY` | Raw chapter rows returned by `Source.getMangaUpdate`, before Tsuzuki snapshot conversion. `received` is the raw row count. |
| `PROBE` | Rows consumed by the Add-on probe, unique evidence accepted, provisional evidence, duplicates or identity-less rows discarded, and provider/binding outcome. |
| `RECONCILIATION` | Chapter evidence received, evidence mapped into canonical chapters, provisional/unmapped evidence, and parser/confidence/identity reasons. |
| `PERSISTENCE` | Canonical chapter count before and after reconciliation, with mapped/unmapped evidence counts in `reasons`. This phase does not query chapter variants or resolve reader options. |
| `UI` | Snapshot of the detail-screen list. `CACHE_SNAPSHOT` marks the current/cached list and `REFRESHED_SNAPSHOT` marks the list after refresh. `accepted` counts displayed real canonical rows; `provisional` and `inferred` are separate. |

`labels` contains only normalized numeric or short semantic chapter labels. `gaps` contains only
integer labels missing between two observed integer labels; it does not assume that chapter 1
exists when a source starts at 138, and it does not generate missing rows. `unavailable` remains
unknown unless the observed boundary can prove unavailability.

Outcome meanings:

- `SUCCESS`: the stage completed with observations and no diagnostic loss condition was detected.
- `EMPTY`: the relevant stage completed but returned no rows/evidence.
- `NO_BINDING`: no available persisted binding was found; this is not an empty source result.
- `TIMEOUT`: a timeout exception was observed.
- `NETWORK_ERROR`: an I/O/network failure was observed.
- `EXTENSION_ERROR`: another extension/provider/parser failure was observed.
- `LOW_CONFIDENCE`: evidence was observed but did not satisfy the canonical mapping threshold.
- `PARTIAL`: some observations were usable while others were duplicated, discarded, low-confidence,
  or failed.

Interpret the first differing boundary as the next investigation target. For example, if
`INVENTORY.received` is 97 with labels 138 and 234, the chapters before 138 did not arrive from the
extension in that refresh; reconciliation and UI cannot recover them. If inventory includes an
earlier label but `PROBE.accepted` or `RECONCILIATION.accepted` drops, inspect the reasons at that
stage. If persistence has the chapter but the refreshed `UI.accepted` does not, inspect UI filters.

An `INVENTORY` or `PROBE` event does not verify a reader page list. The persistence counters also do
not prove that a chapter variant resolves to an installed source or has readable pages. Confirm
actual chapter opening separately on the device if that is needed to establish read availability.

## Privacy and operational invariants

The exported report does not contain raw labels, chapter names, source URLs, source chapter IDs,
content, cookies, tokens, exception messages, or the canonical title ID. It may contain numeric
Mihon source IDs, a technical Add-on ID, language tags, normalized chapter labels, counts, durations,
and closed diagnostic reason/outcome enums. The report is copied only after the user explicitly
starts capture and requests copying.

Instrumentation is observational. The existing operational result/error from fetch, probe,
reconciliation, or refresh is preserved; diagnostic failures are best-effort and do not become
chapter evidence. The detail UI does not infer page availability from a canonical row.

## Changed files

- Domain diagnostic event/interface and safe numeric-label normalization.
- Bounded app-scoped, in-memory diagnostic collector.
- Mihon gateway, chapter probe, provider factory, refresh and reconciliation instrumentation.
- Canonical title screen-model and detail-screen controls for start, stop, copy and clear.
- Focused gateway, probe, refresh, reconciliation, detail-screen and collector tests.

## Validation status

The tests were added and committed first; the initial Fast CI RED was observed for the new test
coverage. No local Gradle tests, local full build, APK build, commit, or push were performed in this
implementation phase. Fast CI remains the authoritative validation gate and must be green before
acceptance. A physical-device run remains necessary to establish the actual installed extension's
One-Punch Man inventory and chapter-page availability.
