# Tsuzuki Development Workflow

## CI-first development

Tsuzuki treats GitHub Actions as the authoritative Gradle/Kotlin build environment. Local machines and phones are primarily editing, orchestration, review, and Git clients.

This is intentional. A contributor must be able to work from a constrained PC or Android/Termux environment without installing Android Studio or depending on local release builds.

## Local responsibilities

Local execution is lightweight by default:

- edit and review source code;
- use Codex/AGY for implementation and review;
- inspect `git diff` and repository state;
- run tiny/focused checks only when they are cheap and the required toolchain is already available;
- commit and push small, reviewable changes.

Java, Android SDK, and full Gradle verification are not mandatory on a constrained development device.

## Three CI levels

Tsuzuki separates normal acceptance, full compilation, and installable artifacts.

```text
FAST CI
every relevant code push
        |
        v
FULL VERIFY
only when technically justified
        |
        v
APK BUILD
only when an installable artifact is useful
```

These levels are intentionally independent. A full verification does not imply that an APK must be stored.

## Fast CI

Every relevant push to `main` or `tsuzuki/**` runs `.github/workflows/ci.yml` with independent jobs for:

- `./gradlew spotlessCheck`;
- `./gradlew :app:compileDebugKotlin`;
- `./gradlew testDebugUnitTest`;
- `./gradlew verifySqlDelightMigration`.

Fast CI is the normal acceptance gate between implementation tasks. Do not continue to the next task while the relevant Fast CI run is red.

Because jobs are independent, formatting, Kotlin compilation, unit tests, and database migration verification can run in parallel and failures remain easy to diagnose.

Failed unit-test reports may be uploaded for diagnosis, but development artifacts use short retention.

## Full Verify

`.github/workflows/build.yml` performs an expensive release compilation without storing an APK artifact.

It runs when:

- a commit message contains `[full-ci]`;
- the workflow is manually dispatched;
- code is pushed to `main`.

The command is:

```bash
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater
```

Use Full Verify when the change has enough integration risk to justify compiling the complete release application, for example:

- the end of a meaningful architectural milestone;
- dependency-injection or generated-code changes;
- Gradle, manifest, build-configuration, or Android integration changes;
- work crossing several application modules;
- Reader/source/platform integration;
- a pre-merge checkpoint when a reviewer requests it.

Do not run Full Verify merely because a task is complete.

To request it:

```text
chore: source resolver checkpoint [full-ci]
```

A `[full-ci]` checkpoint does not upload APK or mapping artifacts.

Pull requests do not automatically trigger Full Verify when opened, synchronized, or marked ready for review. Normal PR iteration remains Fast-CI-first.

## APK Build

`.github/workflows/apk.yml` exists only for cases where an installable application is actually useful.

It runs when:

- a commit message contains `[apk]`; or
- the workflow is manually dispatched.

Tsuzuki supports parallel physical-device testing. The APK lane is selected from the branch name:

| Branch | Variant | Application ID | Device label |
| --- | --- | --- | --- |
| `main`, `tsuzuki/bootstrap` | `release` | `app.mihon` | normal integrated app |
| `tsuzuki/mvp-v1-*` | `deva` | `app.mihon.tsuzuki.deva` | `Tsuzuki Dev A` |
| `tsuzuki/mvp-v2-*` | `devb` | `app.mihon.tsuzuki.devb` | `Tsuzuki Dev B` |

The Dev A and Dev B packages have separate Android app sandboxes, databases, preferences, and app-local files, so both can remain installed on one phone while the reading and Collections tracks are tested independently. The integrated `release` package remains separate from both.

Dev A/Dev B APKs intentionally build without telemetry and without the in-app updater. The integrated release retains the normal telemetry/updater flags. All lanes use the persistent Tsuzuki signing secrets and verify the resulting APK with `apksigner`.

The workflow uploads:

- the ARM64 APK, with the lane included in the artifact name;
- the matching ProGuard/R8 mapping artifact.

Development APK and mapping artifacts use `retention-days: 3`.

Android extension APKs remain device-global rather than app-sandboxed. Disabling a source inside one Tsuzuki install is preferable to physically uninstalling its extension while parallel testing is active, because an uninstall affects both installs. OAuth/deep-link callback isolation must be revisited before parallel tracker/sync testing.

Use APK Build when there is something meaningful to validate on a physical Android device, especially:

- visible UI changes;
- navigation or interaction changes;
- Reader/runtime behavior;
- Android integration that cannot be validated adequately from compile/tests alone;
- a deliberate manual device-test checkpoint.

Do not request `[apk]` for domain-only, repository-only, database-only, resolver-only, or test-only changes unless there is a specific runtime reason.

Example:

```text
chore: title page device test [apk]
```

An APK request is already a release build, so a separate `[full-ci]` commit is not required for the same checkpoint.

## Artifact policy

Build evidence and stored artifacts are different things.

```text
Evidence
-> small and durable: commit SHA, CI result, milestone note

Artifact
-> large and temporary: APK, mapping, failed-test report
```

Normal Full Verify stores no APK. Development APKs and diagnostic test reports expire after three days.

Release artifacts and any future milestone-preservation policy are separate from development CI.

A future CI-housekeeping workflow may record milestone evidence and delete superseded artifacts, artifacts from deleted branches, and other disposable CI output. Do not add such cleanup logic ad hoc to feature workflows.

## Codex / AGY contract

AGY is the orchestrator/reviewer and Codex may act as an implementation worker.

For each implementation task:

1. read the applicable spec and implementation plan;
2. change only the task scope;
3. add or update tests before/with the implementation;
4. review the diff locally;
5. commit and push;
6. inspect Fast CI results and logs;
7. fix only the demonstrated failure scope;
8. continue only after CI acceptance.

Full Verify is requested only when the task or reviewer identifies integration risk. APK Build is requested only when an installable artifact is useful.

Do not substitute an agent's claim that tests "should pass" for CI evidence.

## TDD when developing from a phone

TDD does not require a local Gradle environment. For behavior where red/green evidence matters:

1. commit and push the failing test;
2. confirm the expected failure in Fast CI;
3. add the minimal implementation;
4. commit and push again;
5. confirm the corresponding CI job is green.

This costs an additional CI run but preserves evidence while keeping the phone lightweight.

Focused local Gradle tests remain welcome when convenient, but they are an optimization rather than a prerequisite.

## Git workflow

Create a feature branch and draft pull request early. This gives the work a stable review surface while Fast CI continues to run from pushes.

Recommended shape:

```text
tsuzuki/bootstrap
        |
        +-- tsuzuki/<milestone-or-feature>
                    |
                    +-- small commits
                    +-- Fast CI after relevant pushes
                    +-- [full-ci] only for integration checkpoints
                    +-- [apk] only for device-test checkpoints
```

Do not implement feature work directly on `main`.

## Verification hierarchy

Use the smallest evidence level that proves the current claim:

```text
Focused local check (optional)
        |
        v
Fast CI (required task gate)
        |
        +--> Full Verify (when integration risk justifies it)
        |
        +--> APK Build (when physical-device validation is useful)
```

Full Verify and APK Build are not mandatory after every task or every implementation block.

For Tsuzuki, the physical phone is the preferred runtime test target when runtime testing is actually needed; an Android emulator is not required by the project workflow.

## Relationship to implementation plans

Implementation plans may show exact Gradle commands for reproducibility. On a constrained device, those commands are executed by the corresponding CI job unless the plan explicitly requires a local-only check.

Architecture, test expectations, task boundaries, and acceptance criteria from the plan remain mandatory. This document changes where heavy verification runs and when artifacts are worth keeping, not what correctness means.
