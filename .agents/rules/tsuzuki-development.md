# Tsuzuki development rule

## Goal

Implement Tsuzuki incrementally while preserving Mihon upstream compatibility and using GitHub Actions as the authoritative Gradle/Kotlin verification environment.

## Execution

For each task:

1. Read the active spec, development workflow, and implementation-plan task.
2. Perform one consolidated discovery pass.
3. State the task scope: allowed paths, forbidden paths, acceptance, CI gate, stop conditions.
4. Delegate implementation when useful.
5. Review the diff before pushing.
6. Push a small commit.
7. Wait for CI v2.1 evidence.
8. Accept only if the planner-selected CI jobs are green.
9. On failure, issue a delta retry using only the demonstrated failure evidence.
10. Stop after at most two correction cycles and report BLOCKED if acceptance still fails.

## Constraints

- Do not turn a provider ID into CanonicalTitle identity.
- Do not turn a Mihon Source manga into CanonicalTitle identity.
- Do not rewrite Reader, downloader, extension execution, or source networking unless a later approved plan explicitly requires it.
- Do not add Kitsu, Search UI, Source Resolver, Canonical Chapter Engine, Drive sync, or tracker changes during the canonical-foundation plan.
- Do not perform unrelated cleanup.
- Do not claim verification without CI or an actually executed local check.
- Do not repeatedly poll CI; use a single run wait/watch when possible.
- CI v2.1 affected mode is the normal acceptance gate.
- Request `[ci-full]` only for meaningful integration checkpoints or when the active plan/reviewer requires it.
- `[ci-full]` means one full verification contract: all primary unit-test shards, SQLDelight migrations, Supabase backend tests when present, and release compilation.
- Do not use the retired `[full-ci]` token; the legacy release workflow is manual-only.
- Tsuzuki-only changes should stay on filtered Tsuzuki test shards plus downstream compile checks unless the planner deliberately fails safe to full.
- Request `[apk]` only when an installable artifact or physical-device validation is useful.
- Do not upload APK artifacts for invisible domain/data/test-only work without a concrete runtime reason.

## Evidence output

Return:

- STATUS
- TASK
- FILES_CHANGED
- EVIDENCE
- CI
- ACCEPTANCE
- RISKS
- NEXT
