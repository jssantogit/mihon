# Tsuzuki Post-MVP Deep Audit & Stabilization

**Status:** Ready for execution  
**Date:** 2026-09-19  
**Repository:** `jssantogit/mihon`  
**Audit branch:** `tsuzuki/post-mvp-deep-audit`  
**Frozen baseline:** `7c09c17c35f352a2aa3021911636b51e00afeea8`  
**Parent line:** `tsuzuki/mvp-v3-drive-sync`

## 1. Mission

Perform an intense, adversarial, repository-wide correctness audit of the integrated Tsuzuki MVP foundation before Milestone 13 begins.

The goal is not stylistic cleanup and not feature development. The goal is to make the current foundation measurably safer and more correct by finding, validating, fixing, testing, and re-auditing concrete defects.

The audit is authorized to correct confirmed bugs and correctness defects autonomously while preserving the approved Tsuzuki architecture and Mihon compatibility.

Milestone 13 work is explicitly forbidden during this audit.

## 2. Baseline and execution model

The audit starts from the exact frozen baseline above. At audit creation time, that baseline contains the current integrated MVP-V1 and MVP-V2 state plus the current MVP-V3 Drive Sync head.

MVP-V3 still has a pending physical human acceptance gate. That does not block code audit work. Human-only validation remains deferred and must be reported separately rather than guessed.

Read before making changes:

1. `AGENTS.md`
2. `.agents/rules/tsuzuki-development.md`
3. `docs/TSUZUKI-SPEC.md`
4. `docs/TSUZUKI-DEVELOPMENT.md`
5. all relevant MVP implementation plans under `docs/superpowers/plans/`
6. this audit plan

The Orchestra owns orchestration strategy, worker/model selection, task decomposition, sequencing, and concurrency decisions. This plan defines objectives, boundaries, evidence requirements, and acceptance.

## 3. Core rules

Preserve all architectural invariants, especially:

- Metadata != Source.
- Source Manga != CanonicalTitle.
- Source Chapter != CanonicalChapter.
- Canonical identity != Provider identity.
- Library != Tracker.
- Sync != Backup.
- local state remains usable without cloud availability.
- local user actions update local state before remote synchronization.
- low-confidence identity/source matches never become destructive silent merges.
- Mihon remains the operational Reader/downloader/extension/source foundation.

Do not:

- begin Milestone 13;
- redesign UI for aesthetics;
- perform broad refactors without a demonstrated correctness need;
- replace working Mihon infrastructure merely to make Tsuzuki code cleaner;
- weaken tests to make CI pass;
- delete or rewrite user state to resolve a bug unless the approved architecture requires it;
- treat agent confidence as evidence;
- merge the audit PR automatically.

## 4. What counts as a finding

A finding must describe a concrete correctness or reliability problem.

Good findings include:

- reproducible crash or exception path;
- silent data loss or corruption;
- invalid migration path;
- stale or contradictory persisted state;
- broken merge/conflict/tombstone semantics;
- race condition with a plausible execution path;
- cancellation/lifecycle bug;
- incorrect canonical identity/progress behavior;
- source mapping or chapter mapping inconsistency;
- Drive/auth/revoke/retry failure;
- privacy/security defect;
- release-only/build-variant defect;
- mismatch between tests and actual contract;
- integration regression against Mihon behavior;
- a missing test for a concrete demonstrated bug.

Not findings by themselves:

- naming preferences;
- formatting already enforced by Spotless;
- speculative rewrites;
- generic "could be cleaner";
- architecture alternatives that do not prove current behavior is wrong;
- future feature requests.

Each accepted finding must record:

- ID;
- severity;
- affected files/subsystem;
- violated contract/invariant;
- concrete evidence or reproduction path;
- user/data impact;
- proposed validation;
- disposition: CONFIRMED / DISMISSED / BLOCKED;
- fix commit if corrected.

Suggested severity:

- **P0** — plausible user data loss/corruption, credential/privacy/security exposure, destructive identity/sync behavior.
- **P1** — crash, materially wrong user state, sync/progress/library correctness failure, severe migration/integration defect.
- **P2** — concrete reliability/logic defect with bounded impact.
- **P3** — concrete low-impact defect worth correcting; never use P3 for style-only observations.

## 5. Audit passes

The Orchestra may reorganize the execution, but coverage must include all of the following.

### A. Architecture and ownership

Audit Tsuzuki/Mihon boundaries and canonical ownership.

Attempt to prove violations such as:

- provider IDs becoming canonical identity;
- Mihon manga/source rows becoming canonical identity;
- source chapters becoming canonical progress identity;
- catalog/source abstractions leaking into each other;
- user state depending on derived/recomputable data;
- source-only titles becoming unusable;
- provider/network outages blocking local functionality.

### B. Persistence and migrations

Audit all Tsuzuki SQLDelight schema, migrations, repositories, constraints, indexes, serialization assumptions, and upgrade paths.

Specifically look for:

- fresh-install success but upgrade failure;
- migration ordering collisions;
- missing defaults or invalid NULL transitions;
- foreign-key and delete behavior inconsistencies;
- uniqueness assumptions that can reject legitimate state;
- partial writes;
- non-atomic multi-table state changes;
- stale rows after canonical merge/delete;
- schema/codec version mismatch;
- state that cannot survive process death correctly.

### C. Sync, Drive, merge, conflicts, outbox

Treat sync as a hostile distributed-system boundary.

Attempt scenarios including:

- two devices independently editing the same and different records;
- delete vs edit;
- tombstone resurrection;
- upload succeeds but local acknowledgement fails;
- download succeeds but apply fails;
- retry after partial cycle;
- duplicate retry;
- remote stale manifest;
- local stale base revision;
- conflict persistence/recovery;
- cancellation mid-cycle;
- auth invalidation mid-cycle;
- network loss mid-cycle;
- corrupted/unsupported document;
- deterministic digest/manifest behavior;
- idempotency across repeated cycles.

Verify that cloud failure never rolls back valid local actions.

### D. Google auth and privacy

Audit:

- transient token handling;
- token/account data logging;
- external revocation behavior;
- rejected credential invalidation;
- reconnect/disconnect;
- process death/restoration;
- Play Services failures;
- Drive scope boundaries;
- accidental persistence of secrets;
- device-local tracker credential assumptions.

Never print real credentials or tokens while auditing.

### E. Canonical Library, Source Resolver, Chapters, Reader/progress

Attempt to produce contradictions between:

- Library membership and canonical title existence;
- source mappings and Mihon manga rows;
- preferred source vs title override;
- canonical chapter vs source variants;
- gap/fallback behavior;
- reader resume and page/chapter progress;
- legacy Mihon history and Tsuzuki canonical progress;
- force-stop/restart behavior;
- title deletion/re-addition;
- source disappearance and self-heal.

Try to find a sequence where progress silently moves backward, attaches to the wrong canonical chapter, duplicates, or becomes source-dependent.

### F. Collections / Query Engine

Audit:

- AST serialization/deserialization;
- provider capability compilation;
- residual filtering;
- pagination correctness;
- deduplication;
- cache invalidation;
- scheduler cancellation and concurrency;
- import/export validation;
- malformed/old JSON;
- folder/list ordering and deletion;
- local persistence vs synced representation;
- unsupported predicates being silently ignored.

### G. Concurrency, coroutines, lifecycle

Inspect long-lived jobs, flows, mutex/locking assumptions, shared state, cancellation, retry, background work, and UI/runtime boundaries.

Look for:

- duplicate work;
- lost wakeups/updates;
- concurrent mutation of the same state;
- jobs surviving the wrong lifecycle;
- swallowed cancellation;
- exception paths leaving runtime state stuck;
- manual and background sync overlapping incorrectly;
- "running" diagnostics that can never recover.

### H. Build, variants, release behavior

Audit:

- Dev A/B/C vs integrated release differences;
- manifest/provider/deep-link differences;
- R8/ProGuard-sensitive code;
- DI/generated code;
- updater/telemetry flags;
- Google callback/package isolation;
- code paths covered only in debug;
- configuration that compiles but fails in release.

### I. Upstream Mihon compatibility

Inspect Tsuzuki modifications to Mihon-owned surfaces.

Look for:

- behavior changes outside required adapters;
- assumptions likely to make upstream merges fragile;
- Reader/downloader/extension regressions;
- extension-global vs app-sandbox assumptions;
- legacy tracker/history interactions that bypass Tsuzuki state incorrectly.

Do not refactor upstream code merely for cleanliness.

### J. Test-quality audit

Do not only count tests. Inspect whether tests prove the contracts they claim.

Look for:

- tests reproducing implementation rather than behavior;
- important branches with no assertion;
- fake behavior materially different from production adapter;
- migration tests that miss real upgrade paths;
- concurrency tests that cannot expose ordering bugs;
- sync tests that omit retries/partial failure/idempotency;
- tests passing because invalid states are impossible in fixtures but possible in production.

Add focused regression tests for confirmed bugs whenever practical.

## 6. Adversarial review requirement

Do not immediately fix every suspicious observation.

For material findings, perform an explicit challenge step before correction:

1. state the suspected defect;
2. try to disprove it using surrounding code/contracts/tests;
3. reproduce it with a focused test or establish a concrete execution path;
4. mark it CONFIRMED only when evidence survives that challenge.

P0/P1 findings require especially strong validation.

False positives should be recorded as DISMISSED with a brief reason so later passes do not rediscover the same non-bug repeatedly.

## 7. Correction policy

For a confirmed defect:

1. prefer a regression test that fails for the demonstrated behavior;
2. implement the smallest correction consistent with the architecture;
3. avoid unrelated cleanup;
4. review the diff;
5. commit a small, descriptive change;
6. require Fast CI before considering that correction accepted.

Fast CI remains authoritative:

- Format
- Kotlin Compile
- Unit Tests
- SQLDelight Migrations

Use actual failure evidence for correction. Do not guess at red CI.

If a fix remains blocked after two evidence-driven correction attempts, record it as BLOCKED and continue auditing other independent areas rather than stalling the entire overnight run.

Request Full Verify for fixes with meaningful cross-module/release/integration risk and for the final audit checkpoint.

Do not request an APK merely to satisfy this automated audit. Physical-device validation belongs to the human gate unless a generated APK is specifically necessary to prove a discovered runtime bug.

## 8. Continuous audit ledger

Create and maintain:

`docs/audits/2026-09-19-post-mvp-deep-audit.md`

The ledger must contain:

- frozen baseline SHA;
- audit coverage completed;
- confirmed findings;
- dismissed findings;
- blocked findings;
- fix commits;
- CI evidence;
- residual risks;
- human-only validation still required;
- final audited HEAD.

Keep the ledger factual. Do not inflate finding counts.

## 9. Re-audit after corrections

After the first correction wave:

- inspect the entire cumulative diff from the frozen baseline;
- re-run adversarial reasoning against the corrected areas;
- specifically search for regressions introduced by fixes;
- revisit interactions between subsystems rather than only isolated modules;
- run any focused tests needed to challenge the new state.

A correction is not considered final merely because its original regression test passes.

## 10. Final automated gate

The automated portion is complete only when:

1. every required audit area has been covered or explicitly marked BLOCKED with reason;
2. all confirmed fixable P0/P1 issues found during this run are fixed or explicitly blocked with evidence;
3. accepted fixes have green Fast CI;
4. migration verification is green;
5. the cumulative audit diff has been re-reviewed;
6. a final Full Verify succeeds on the final audited code when code changed;
7. the ledger is complete;
8. remaining physical-device/human checks are listed clearly;
9. no Milestone 13 feature work entered the branch.

Do **not** merge automatically.

The overnight run may finish with residual P2/P3 or human-only items if they are clearly documented. Never hide them to claim success.

## 11. Stop conditions

Stop autonomous modification and report clearly if:

- a correction would require changing an approved product invariant;
- evidence suggests actual user data could be destroyed by continuing;
- repository/CI credentials or external services prevent safe verification;
- a bug requires a product decision rather than a correctness decision;
- the baseline unexpectedly changes underneath the audit;
- human physical-device interaction is required to establish the next fact.

Otherwise continue independently through the audit, validation, correction, CI, and re-audit loop without waiting for human prompts.

## 12. Final report format

At completion, return:

```text
STATUS: DONE | DONE_WITH_BLOCKERS | BLOCKED | FAILED
BASELINE:
FINAL_HEAD:

COVERAGE:
- ...

CONFIRMED_FINDINGS:
- ID / severity / summary / fix commit / evidence

DISMISSED_FINDINGS:
- ID / summary / reason

BLOCKED_FINDINGS:
- ID / severity / blocker / recommended next action

CI:
- Fast CI:
- Full Verify:

HUMAN_GATES_REMAINING:
- ...

RESIDUAL_RISKS:
- ...

NEXT:
- whether the foundation is ready for final human MVP-V3 acceptance and integration
- do not start Milestone 13
```

## 13. Launch instruction

The Orchestra should treat this as a long-running autonomous stabilization mission.

Do not optimize for number of changes. Optimize for confidence in the correctness of the Tsuzuki foundation.
