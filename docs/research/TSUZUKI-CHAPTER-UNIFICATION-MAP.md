# Tsuzuki chapter reconciliation: production caller map and migration gate

Status: **single-writer cutover implemented and automated pre-UI acceptance complete in PR #22**. The production implementation baseline is `6c173c41e7cdbd6134ff3c2e4b66ca2c0ae2b426`. PR #20/#21/#22 remain draft and unmerged; physical-device and bounded live-provider evidence remain external acceptance debt.

Sections below that discuss `ReconcileChapterInventory` as an active writer document the **historical pre-cutover path** and its migration evidence. They must not be interpreted as the current production architecture.

## Current production paths

| Entry point | Refresh and input | Canonical writer | Result |
| --- | --- | --- | --- |
| `ReaderViewModel.attachCanonicalSessionForLegacy` | On an unmapped legacy Mihon chapter, calls `RefreshCanonicalChapters.execute(title, mappingId)` through `ChapterInventoryGateway` / `MihonChapterInventoryGateway` | `ReconcileLegacyChapterEvidence → ReconcileChapterEvidence.executeAndProject` | Legacy Mihon inventory becomes source-scoped chapter evidence; canonical chapter/evidence and operational `ChapterVariant` projection share the evidence-authoritative transaction and mutation gate. |
| `CanonicalTitleScreenModel.refreshInBackground` | `RefreshChapterEvidence.execute(title)` | `ReconcileChapterEvidence` | Transactional canonical chapter and persisted editorial/Add-on evidence; post-commit invalidation prevents stale in-flight/content-option reuse. |
| `ContentSelectorScreenModel.refreshAfterBindings` | `RefreshChapterEvidence.executeForBinding(binding)`, bounded to selected bindings | `ReconcileChapterEvidence` | Same canonical chapter/evidence authority with title/Add-on-scoped invalidation; no all-provider refresh is introduced by this targeted path. |
| Targeted initial Reader discovery | `DiscoverReadableChapter`, `ResolveChapterContent` and scoped evidence refresh | `ReconcileChapterEvidence` when refresh is necessary | Uses verified per-chapter options; a verified option is not proof that full image bytes have rendered. |

There is now **one canonical reconciliation authority**: `ReconcileChapterEvidence`. `ReconcileLegacyChapterEvidence` is a compatibility adapter/projector for materialized Mihon inventory, not a second identity writer. The independent `ReconcileChapterInventory` implementation was removed in PR #22.

## Reused source URLs: safety guard before writer unification

The original legacy reconciler reused a persisted `ChapterVariant` solely
because `(sourceId, sourceChapterId)` was unchanged, even when a later
observation described a reliably different chapter or explicitly contradictory
volume. The evidence reconciler already detects those conflicts and can remap
source-scoped evidence to the newly supported identity.

**The acceptance branch now fails closed in the legacy writer:** it parses
existing source-key observations, checks reliably contradictory chapter
identities and explicitly incompatible non-null volumes, and rejects the
affected inventory *before* committing any batch. Existing canonical IDs,
progress and independent source variants remain intact. It also rejects a
persisted variant pointing to another canonical title. This guard prevents
silently reaffirming a known-wrong old association, but does **not** resolve
the reused source URL automatically: old operational variants remain on disk
until a shared, transactionally safe evidence/variant migration is implemented.

A RED→GREEN regression reproduces an existing URL changing from volume 1
chapter 4 to volume 2 chapter 4 while a second source still supports the old
chapter. Two older tests were updated to assert fail-closed behavior instead
of unconditional trust in the reused URL. The [focused Domain and App CI
36271257460](https://github.com/jssantogit/mihon/actions/runs/36271257460)
passed on equivalent production and test source blobs; skipped Format,
Release and migration jobs in that run do not count as validation.

Full unification is still blocked on legacy Reader acceptance, operational
variant projection in the same database transaction as evidence, and
conflicting-key compatibility coverage.

## Migration sequence — completed by PR #22

1. Record equivalence fixtures against *both existing writers*: fractional
   chapters (0/0.5/1), same number in different volumes, two languages
   pointing to one chapter, reused external keys, partial inventory, empty
   or disabled sources, refresh concurrency, and an unmatched old Mihon
   chapter. Assert canonical IDs and exact operational variant mapping.
2. Implement a narrow legacy `SourceChapterInventory` adapter producing
   source-scoped provisional `ChapterEvidence` with stable keys and explicit
   volumes. A legacy Mihon mapping may exist without a new `ContentBinding`;
   do not silently discard these readers during migration.
3. Route canonical identity and evidence persistence through
   `ReconcileChapterEvidence`. Ensure the required `ChapterVariant` for a
   legacy Reader is projected from its **persisted mapped evidence** with the
   original Mihon source/mapping identity, in the same relevant database
   transaction. Do not create a second fallback canonical writer.
4. Migrate `ReaderViewModel.attachCanonicalSessionForLegacy` and then
   `RefreshCanonicalChapters` callers behind the adapter. Preserve
   materialized Mihon IDs and successful loaded-page sessions when
   reconciliation or a provider fails; never guess the mapping.
5. Remove `ReconcileChapterInventory` as an independent canonical writer
   only after all callers and equivalence tests have passed. Preserve
   compatibility adapters that serve genuinely operational Mihon rows.

## Executed pre-migration equivalence baseline

- `ReconcileChapterEvidenceTest` now contains a dual-writer characterization using two explicit volume identities and two Mihon source IDs. The comparison seeds the *same existing canonical chapter IDs* in isolated legacy/evidence stores, checks both writers' source-to-chapter decisions and verifies that a repeated refresh retains legacy variant IDs.
- The Domain job passed in [CI 36267018356](https://github.com/jssantogit/mihon/actions/runs/36267018356) with byte-identical matcher, both reconcilers, volume/label parsers, and test sources on the acceptance PR and validation branch.
- A second parity test exercises chapters 0, 0.5 and 1, then refreshes with the middle observation **omitted**. Both writers preserve the middle chapter; the legacy source variant and persisted evidence retain their original mappings. [Domain tests passed in CI 36267649886](https://github.com/jssantogit/mihon/actions/runs/36267649886).
- A third parity test seeds two provider mappings for the same preexisting volume 1 chapter 4, empties one source's inventory and then both inventories, and asserts that both canonical writers preserve the established chapter ID, both legacy variant IDs and the mapped evidence for both sources. Domain, Data, App, Core, SQLDelight, Supabase and Format passed in the [PR-equivalent full verification 36273234659](https://github.com/jssantogit/mihon/actions/runs/36273234659); Release and CI Gate also passed; separate compile matrix and Native Package Gate were skipped by the planner. This is not an assertion about live provider completeness.
- A fourth dual-writer test covers **two source languages** (English and pt-BR) sharing one preexisting volume-1 canonical chapter while retaining separate legacy `ChapterVariant` IDs and language metadata. It reverses refresh ordering and later omits the pt-BR row; neither writer may drop the previous mapping. The [Domain job in CI 36282164099](https://github.com/jssantogit/mihon/actions/runs/36282164099) passed on byte-identical PR source and test blobs; the remaining full CI jobs require separate checking.
- The baseline is still intentionally narrow. It does not cover the legacy Reader UI, reused external URLs, contradictory volume releases, a transaction spanning evidence and operational variants, or Android E2E. Do not use it as authorization to remove the older writer.

## Legacy inventory evidence adapter — historical staging record

The acceptance PR now includes `LegacyInventoryEvidenceAdapter`, which maps a previously materialized Mihon source inventory to `ADDON_PROVISIONAL` evidence **without requiring a new ContentBinding**. Its producer scope includes the canonical title and Mihon source ID, so the same relative URL in a different title/source cannot accidentally reuse evidence; its generated evidence ID is deterministic across refresh order and label changes. Only explicit, unambiguous volume-prefix evidence becomes `volume`; the old numeric hint remains a hint, never verified editorial authority. Identical repeated chapter URLs are deduplicated; contradictory duplicate URL observations and mismatched source/mapping IDs fail closed before any write. Blank source ID may use a nonblank legacy source URL; both blank fail.

The [initial RED CI 36282830973](https://github.com/jssantogit/mihon/actions/runs/36282830973) executed all four new adapter tests and they failed against the intentionally unimplemented adapter. The subsequent [Domain CI 36282997303](https://github.com/jssantogit/mihon/actions/runs/36282997303) passed those tests and a new end-to-end test through the existing evidence reconciler: an English source reuses a URL for volume 2 while a Portuguese source retains independent support for volume 1, preserving both canonical IDs. Spotless reported one whitespace layout difference in the adapter; the corrected implementation is under [CI 36283160529](https://github.com/jssantogit/mihon/actions/runs/36283160529) passed Domain, Data, App, Core, SQLDelight, Supabase, Format, Release and CI Gate; separate Compile/Native gates were skipped by the planner.

**Historical note:** at this checkpoint the adapter was not connected to the legacy Reader. PR #22 later connected `RefreshCanonicalChapters` to `ReconcileLegacyChapterEvidence`; see the final cutover evidence below.

## Atomic projection — historical staging record

The acceptance PR now includes `ReconcileLegacyChapterEvidence`. After source-scoped provisional evidence is created by the legacy adapter, `ReconcileChapterEvidence.executeAndProject` reconciles canonical chapters/evidence and projects only the **persisted mapped evidence** to Mihon `ChapterVariant` rows. Everything runs within the same SQLDelight transaction and chapter mutation gate. An unmapped observation creates no variant; if an already-mapped key becomes ambiguous the projector fails closed and the transaction rolls back. Stable operational variant IDs and source metadata are retained across reorder and explicit-volume reassignment; independent support for the old volume is preserved.

The [RED JDBC CI 36284495531](https://github.com/jssantogit/mihon/actions/runs/36284495531) confirmed three new regressions fail against the deliberately unimplemented projector. [Scoped GREEN CI 36284727291](https://github.com/jssantogit/mihon/actions/runs/36284727291) passed Domain, Data, App, Format and CI Gate. The real SQLite foreign-key regression fails on the **second variant** after an earlier valid variant and newly written canonical/evidence rows; it confirms complete rollback, preservation of preexisting state and successful retry with the mapping fixed. Scope-planned migration/Release/Supabase/Native and separate Compile jobs were skipped, not approved; a full CI run on byte-identical PR files remains required.

This paragraph records the pre-cutover gate at that historical checkpoint. PR #22 subsequently routed the production legacy Reader compatibility entry through this projector and removed the independent legacy writer after the final-SHA gates described below. No distribution APK or merge is implied by the historical staged API.

## Mapping authority and cross-writer serialization — historical pre-cutover evidence

The staged legacy projection now reads the persisted `SourceTitleMapping` **inside** its canonical/evidence/variant transaction. It accepts an inventory only if the mapping ID belongs to the same canonical title, the Mihon source ID and materialized manga ID match, the mapping is not unavailable and nonblank inventory language matches. A rejected inventory rolls back staged canonical/evidence writes and never publishes an operational variant. [RED JDBC CI 36285954535](https://github.com/jssantogit/mihon/actions/runs/36285954535) reproduced three previously accepted invalid inventories; [GREEN scoped CI 36286206067](https://github.com/jssantogit/mihon/actions/runs/36286206067) passed Data, Domain, App, Format and CI Gate.

While both writers coexist, the injected `ReconcileChapterInventory` now locks the same AppScope `ChapterMutationGate` already used by `ReconcileChapterEvidence`. A deterministic test held the gate to simulate an active competing reconciliation: the unprotected legacy writer wrongly completed before release ([RED Domain 36286180551](https://github.com/jssantogit/mihon/actions/runs/36286180551)); the corrected writer waited and Domain/App/CI Gate passed in [scoped GREEN 36286381163](https://github.com/jssantogit/mihon/actions/runs/36286381163). This is serialization proof, **not** full end-to-end proof of simultaneous Reader/detail UI refresh.

**Identity and ordering gates now covered:** the neutral inventory DTO records the exact source title URL. Persisted title/source/URL/manga/availability validation runs inside the same projection transaction, including empty inventories. The staged projector serializes with the legacy writer through the shared mutation gate; old evidence cannot overwrite a newer persisted observation. The Mihon gateway now attaches the original fetch-start timestamp, which stays unchanged in cached inventories; the legacy evidence adapter retains it through later reconciliation and an equal-timestamp conflicting legacy release is rejected while identical cache replays remain idempotent.

[PR-equivalent full CI 36313408103](https://github.com/jssantogit/mihon/actions/runs/36313408103) passed mapping, locking and empty-projection coverage. [RED provenance CI 36313988598](https://github.com/jssantogit/mihon/actions/runs/36313988598) exposed missing provider fetch timestamps in App/Domain and a cached stale overwrite in real Data/JDBC. [Scoped GREEN 36314684084](https://github.com/jssantogit/mihon/actions/runs/36314684084) passed App/Domain after preserving the original fetch clock. [RED same-millisecond JDBC 36314850321](https://github.com/jssantogit/mihon/actions/runs/36314850321) then demonstrated an ambiguous equal-time rehome. [Full CI 36315380750](https://github.com/jssantogit/mihon/actions/runs/36315380750) passed Domain, Data, App, Core Common, Format, SQLDelight, Supabase, Release Compile and CI Gate with the corrected Mihon-legacy-only guard; separate Compile/Native jobs were skipped by the planner. Verified that all eight PR source/test blobs are byte-identical to the disposable green branch; none of its CI workflows were promoted.

**Historical checkpoint:** these items were still pending here. PR #22 subsequently completed final-SHA Reader/detail races, Android process recovery, Reader 9/9, the 100/500/1,000 projector benchmark and removal of the independent legacy writer. Live-provider inventory and physical-device evidence remain separate.

## Cached legacy Reader versus canonical detail — CR-01 pre-cutover proof

An App integration regression uses the production `MihonChapterInventoryGateway`, `RefreshCanonicalChapters` and `RefreshChapterEvidence` callers against a local HTTP fixture. It warms the Reader inventory with Chapter 1, delays the legacy refresh after it returns the cached inventory, then lets a forced detail refresh record Chapter 2 evidence for the same exact source chapter key before releasing the legacy write. The initial [RED CI 36328774260](https://github.com/jssantogit/mihon/actions/runs/36328774260) showed the detail evidence mapped to canonical ID `d0836135-58d9-45d9-936f-fbde8ec47727` and then the same-key operational variant mapped to a different Chapter 1 ID `493b477c-dba1-4111-8ccd-5e3baf71edcb`.

`ReconcileChapterInventory` now reads ADDON evidence while holding the shared mutation gate and suppresses an old inventory unless its parsed identity and volume prove it equivalent to the latest same-title/source/exact-key mapped evidence. Unmapped newer evidence and contradictory evidence tied at the latest timestamp also block old projections; semantically identical tied replay remains allowed, and a genuinely later provider fetch can advance. The production-injected evidence repository is required, so a failed evidence read cannot silently fall back to the legacy writer. The added edge regressions returned the expected RED in [CI 36330781034](https://github.com/jssantogit/mihon/actions/runs/36330781034): Domain failed three cases for newer unmapped evidence and order-dependent contradictory ties, while App passed. The corrected Domain tests cover unmapped evidence, timestamp equality, both tie orderings and identical replay. The App fixture also asserts that the delayed observation leaves persisted mapping, existing chapter/variant IDs, preferred source mapping, progress and history intact, and separately confirms same-identity cached replay keeps its existing variant.

[GREEN CI 36331109111](https://github.com/jssantogit/mihon/actions/runs/36331109111) passed Domain Tsuzuki, App Tsuzuki and CI Gate on code SHA `3d3d058ef1caa25a85bbcf764e2482289c198359`. Format, migrations, Supabase, Release, separate Compile and Native jobs were planner-skipped; APK Build was skipped. The test uses a local source harness and in-memory canonical/evidence repositories. It invokes the actual production interactors and gateway, but does not run `ReaderViewModel`/Reader UI, Android instrumentation, process restart, a cancelled/invalidation race, or any real-provider sweep. This closes the cached late-response integration regression only; it does not close phase 1 or authorize cutover.

## Reader/detail owner races and partial-source recovery — CR-06 pre-cutover checkpoint

Branch `tsuzuki/uc01-transactional-cutover`, implementation checkpoint SHA
`439e7b68bf1224c72969d548a7adc34f4f1081c6` (before the final documentation
and recovery-workflow checkpoint commit). The gateway now performs the
synchronous Mihon source inventory call on `Dispatchers.IO`; source-fetch
start time and cancellation behavior remain tied to that call. A focused
gateway regression waits on an explicit `CompletableDeferred` signal from
inside the old in-flight source fetch before starting the competing request.
This replaces a scheduler `yield()` that did not prove IO work had started,
while retaining the invalidation and newer-inventory assertions.

The local partial-source App fixture now queues the second English HTTP 503
needed for the provider's uncached retry. Previously its retry waited forever
for a MockWebServer response, and the two-language provider lookup timed out
before exposing the valid cached pt-BR option. This is a fixture correction;
the exact `pt-BR` expectation remains, no production timeout was enlarged,
and the test's provider and content-selector assertions remain enabled. The
focused CI run [36374013417](https://github.com/jssantogit/mihon/actions/runs/36374013417)
passed App, Domain and CI Gate on SHA `439e7b68bf1224c72969d548a7adc34f4f1081c6`;
Format and unrelated planner-selected jobs were skipped in that run.

The offline synthetic-emulator run
[36372581923](https://github.com/jssantogit/mihon/actions/runs/36372581923)
executed both detail-owner race methods: cancellation and invalidation. Each
XML result reports one test, zero failures, zero errors and zero skips. Both
loaded ten pages and retained observable position, canonical IDs, source
variant, preference, progress and history; cancellation released the held
response and invalidation released its late response. The parent reviewer
accepted this 2/2 behavioral proof. It is a focused run on SHA
`165bbe668487037be74096f06327b81e90093c01`, not final-SHA evidence. Final
Android base coverage, repeat detail-race run, process recovery, full CI and
the 100/500/1,000-observation benchmark remain pending on the final checkpoint
SHA. Physical-device acceptance and live-provider validation remain separate;
neither is claimed here.

The process-recovery workflow now has a manual dispatch entry point, committed
with this checkpoint. Next, run all required final gates against the resulting
SHA and record their exact results.

## Final cutover evidence — PR #22

Production implementation baseline: `6c173c41e7cdbd6134ff3c2e4b66ca2c0ae2b426`.

- [Full CI 36381251731](https://github.com/jssantogit/mihon/actions/runs/36381251731) passed App, Domain, Data, Core Common, Format, SQLDelight migrations, Supabase, Release Compile and CI Gate. Compile matrix and Native Package Gate were planner-skipped, not passed.
- [Final Reader Android 36381449656](https://github.com/jssantogit/mihon/actions/runs/36381449656) executed 9/9 methods on an isolated offline emulator with synthetic local fixtures and `providerCalls=0`, including legacy canonical attach in addition to the eight source-switch/lifecycle cases.
- [Reader/detail races 36382698722](https://github.com/jssantogit/mihon/actions/runs/36382698722) executed 2/2 methods through the real `CanonicalTitleScreen` / Reader lifecycle with shared AppScope inventory cache. Detail-owner cancellation and explicit inventory invalidation both preserved loaded Reader pages, canonical IDs, mapping, prior variant, preference, progress and history while rejecting the stale target variant.
- [Process recovery 36381426672](https://github.com/jssantogit/mihon/actions/runs/36381426672) proved a real process transition (`PID 3047 → 3851`), a `PENDING` durable projection queue after force-stop, database verification before startup, startup replay to `ACKNOWLEDGED`, preserved preference/progress and history applied once.
- [Final-SHA projector benchmark 36380749934](https://github.com/jssantogit/mihon/actions/runs/36380749934) passed at 100/500/1,000 already mapped chapters with 704/3,504/7,004 SQL operations and stable existing variant IDs.

### Acceptance-contract resolution

The Reader/detail race and process-recovery requirements are accepted as **compositional evidence**, not misreported as one monolithic E2E test. The race tests prove that the UI concurrency paths leave the durable canonical/mapping/variant/user state stable after cancellation or invalidation; the recovery test independently proves that pending canonical→Mihon projection work survives an actual process death and replays once at startup. A future literal `race → force-stop → restart` scenario may be added as hardening, but it is not a pre-UI blocker.

The performance gate is intentionally **projector-level**. The benchmark measures `ReconcileLegacyChapterEvidence` and its SQL projection on in-memory SQLite; it does not measure provider fetches, gateway/cache latency, Android disk I/O or whole-Reader performance. Targeted routing and the no-broad-provider-sweep invariant are functional properties covered by integration/Android tests, not inferred from this microbenchmark. No whole-Reader performance claim is authorized by the benchmark numbers.

The automated pre-UI cutover gate is therefore complete. External acceptance remains open for a physical Android device and bounded real-provider evidence (Death Note, One-Punch Man, Hunter × Hunter, Dandadan and Nanatsu no Taizai). The stacked PRs remain draft/unmerged until the project explicitly chooses the merge gate.

## Acceptance checks

- Fault-inject between canonical chapter, evidence and operational variant
  writes: rollback all affected rows without deleting old correct mappings.
- Replays and simultaneous legacy/canonical refresh must retain stable
  IDs, avoid double variants and preserve chapter order, volumes, history
  and preferred source.
- Re-run 100/500/1,000-observation benchmarks against PR #20. Never
  restore the previous per-observation full-title evidence reads.
- An unavailable extension or a chapter missing from one source must not
  delete other-source evidence. A low-confidence observation must not
  create a falsely verified reading option.
- No broad all-source fetch is introduced by a targeted per-binding refresh.
- Run Domain/Data/App, SQLDelight migration, and all eight instrumented
  Reader tests on the **same final production implementation SHA**.
  Distinguish a compiled or skipped test from an executed Android pass.

## Historical PR #21 Android acceptance — superseded by final PR #22 gates

[Final offline emulator run 36280293418](https://github.com/jssantogit/mihon/actions/runs/36280293418) executed **all eight named tests, 8/8 PASS**, with strict instrumentation/result parsing and real on-screen red/blue pixels linked to attached decoded page views: A→B retains index 4; empty/503 replacement retains prior session; 10→3 pages clamps 8→2; retired callbacks cannot overwrite newer state; Activity recreation restores index 6; repeated A↔B preserves one history row; slow source cannot block healthy source; canceled discovery cannot mutate the active reader. The [full CI 36280293408](https://github.com/jssantogit/mihon/actions/runs/36280293408) passed Domain/Data/App/Core, SQLDelight migration, Supabase, Format, Release and CI Gate; separate Compile matrix and Native Package Gate were skipped, not approved. The disposable test SHA [`bd95095`](https://github.com/jssantogit/mihon/commit/bd95095e9a33127b032f42944fb13ffcba9d1de9) has byte-identical blobs to PR #21's three production Reader files and instrumented test; CI routing/workflow files differ to trigger the offline run. An exact-title Pixel Launcher system-ANR dismissal was added to the test fixture without relaxing focus or screenshot requirements.

**Historical scope:** that 8/8 run closed only PR #21's original emulator cases. PR #22 later added legacy attach, production cutover, final-SHA process recovery and Reader/detail race coverage as recorded above. Physical-device visual evidence and live-provider inventory remain unverified; synthetic 169/163 inventory fixtures still do not explain a current live-provider discrepancy.
