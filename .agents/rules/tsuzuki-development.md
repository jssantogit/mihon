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
7. Wait for Fast CI evidence.
8. Accept only if the relevant CI jobs are green.
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
