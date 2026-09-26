# Tsuzuki chapter acceptance — staged PR #21

This acceptance work is based on `codex/tsuzuki-chapter-integrity` (PR #20) and is not merged into Fast. Do not begin the major UI redesign until behavioral acceptance is established. CI-only Gradle; retain isolated Android fixtures.

## A. Android source-switch acceptance (in progress)

- [x] Add eight opt-in, offline instrumented Reader scenarios with synthetic A/B sources and real application/Reader wiring; preserve strict sanitized evidence verification.
- [x] Execute eight methods on disposable emulator. **Result 0/8** in [36251551981](https://github.com/jssantogit/mihon/actions/runs/36251551981): screenshot evidence failed in seven cases; source A→B hit an unresponsive navigator tap before switching. Pages, streams, attached and ready image views, and some UI page positions were observed. These are diagnostics, **not** E2E passes.
- [x] Add explicit disposable-fixture secure-screen opt-out with restoration and safe `windowSecure` / `windowHasFocus` diagnostics; preserve screenshot pixels **and** attached decoded image-view requirements.
- [ ] Run full CI and focused Android fixture at corrected HEAD; inspect real secure-window/focus/color evidence and investigate the first failing assertion without suppressing visual proof.
- [ ] Repair actual focus/overlay/navigation/screenshot blocker, then demonstrate physically rendered A/B pages and successful valid→valid source selection.
- [ ] Execute all eight instrumented cases green with observed page positions, image evidence, no old-session loss, source preference and canonical history/progress assertions. Capture the exact validated SHA and Android run URL.
- [ ] Repeat a limited real-phone sanity check where practical; distinguish emulator-only evidence from live extension or device evidence.

## B. Durable canonical→Mihon projection (not started)

- [ ] Add injected-failure regression tests for progress and history projection, crash/restart, idempotent delivery and monotonically ordered operations.
- [ ] Correct projection error observability: the current `HistoryRepositoryImpl.upsertHistory` swallows write errors, so projection failure must be surfaced by a strict, compatible write path.
- [ ] Persist pending projection state in the same transaction as the canonical checkpoint; add migration and old-database migration tests if schema changes.
- [ ] Implement bounded, resumable, privacy-respecting recovery. Avoid double-addition of Mihon/canonical time-read duration or stale progress replay. Do not block Reader rendering.
- [ ] Run Domain/Data/App, migration and Reader history tests on GitHub CI.

## C. Real-flow source integration (not started)

- [ ] Full deterministic Death Note manual binding→targeted refresh→confirmed chapter 1→Reader list-of-pages with empty peer provider, including idempotency.
- [ ] Title- and session-correlated One-Punch Man raw 169 versus provisional 163 diagnostic; distinguish duplicate/filter/parse/special/low-confidence/unavailable causes without fabricating inventory.
- [ ] Re-run volume, fractional, partial inventory, disabled source, exception and concurrent refresh scenarios.
- [ ] Separate synthetic-fixture evidence from bounded opt-in real-provider and real-phone verification. Dandadan cropping still requires actual image/display evidence.

## D. Single authoritative reconciliation (blocked on acceptance)

- [ ] Map live callers of legacy inventory and newer evidence refresh.
- [ ] Add behavioral-equivalence tests for legacy Reader entry and canonical detail/library refresh.
- [ ] Make canonical evidence reconciliation authoritative; retain only required Mihon operational adapters. Remove duplicate decision/write flows once all callers pass.
- [ ] Re-run 100/500/1000 observation benchmark; preserve stable IDs, transaction rollback, selective refresh, no extra all-source sweeps.

## E. Final validation

- [ ] Full CI jobs (distinguish skipped gates), offline Android evidence on final implementation SHA, OpenSpec updates, code review and separate signed-off recommendations for UI.
- [x] Maintain draft [PR #21](https://github.com/jssantogit/mihon/pull/21) stacked on draft PR #20, without merging either.
