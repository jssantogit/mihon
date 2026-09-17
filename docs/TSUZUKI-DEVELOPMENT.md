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

## Fast CI

Every push to `main` or `tsuzuki/**` runs `.github/workflows/ci.yml` with independent jobs for:

- `./gradlew spotlessCheck`;
- `./gradlew :app:compileDebugKotlin`;
- `./gradlew testDebugUnitTest`;
- `./gradlew verifySqlDelightMigration`.

These jobs are the normal acceptance gate between implementation tasks. Do not continue to the next task while the relevant Fast CI run is red.

Because jobs are independent, formatting, Kotlin compilation, unit tests, and database migration verification can run in parallel and failures remain easy to diagnose.

## Full build and APK

`.github/workflows/build.yml` owns expensive release builds and APK artifacts.

A full release build runs when:

- a pull request is non-draft;
- a push commit message contains `[full-ci]`;
- the workflow is manually dispatched;
- code is pushed to `main`.

The build command is:

```bash
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater
```

A successful build uploads the ARM64 APK and mapping artifact.

Keep implementation pull requests in draft while actively iterating. Mark the PR ready for review only when repeated full release builds are useful.

To request an APK checkpoint while a PR is still draft, use a commit message such as:

```text
chore: checkpoint canonical foundation [full-ci]
```

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
                    +-- Fast CI after pushes
                    +-- [full-ci] checkpoints when needed
                    +-- mark PR ready near completion
```

Do not implement feature work directly on `main`.

## Verification hierarchy

Use this order of evidence:

```text
Focused local check (optional)
        ↓
Fast CI (required task gate)
        ↓
Full release build (checkpoint / review gate)
        ↓
Install APK on a physical Android device
```

For Tsuzuki, the physical phone is the preferred final runtime test target; an Android emulator is not required by the project workflow.

## Relationship to implementation plans

Implementation plans may show exact Gradle commands for reproducibility. On a constrained device, those commands are executed by the corresponding CI job unless the plan explicitly requires a local-only check.

Architecture, test expectations, task boundaries, and acceptance criteria from the plan remain mandatory. This document changes where heavy verification runs, not what must be verified.
