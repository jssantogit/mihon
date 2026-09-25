# Tsuzuki AI workflow — Android verification

**Scope:** Instructions for Codex and other development agents working in this repository. Read alongside `AGENTS.md`, `docs/TSUZUKI-DEVELOPMENT.md`, and `.agents/rules/tsuzuki-development.md`.

## CI and Android testing

- Follow CI-first development and the active task plan. Use affected CI v2.1 as the ordinary code gate. Use `[ci-full]` when an integration checkpoint justifies full verification; do not use the retired `[full-ci]` token.
- **GitHub Actions Android emulators are allowed** for isolated, opt-in instrumentation. This includes deterministic tests for activity navigation/recreation, the Reader-to-title discovery route, extension registration, source enable/disable, and disposable SQLDelight persistence.
- Emulator-side ADB is allowed in those CI scripts. This permission does **not** authorize automating the user's personal phone; physical-device tests are manual unless the user separately authorizes device automation.
- Real third-party extension E2Es must be explicitly authorized by the user and gated by workflow dispatch or another documented opt-in. Limit requests to the approved extension, language, work, and chapter; use backoff, bounded retries and sanitized diagnostics. Do not run mass provider matrices or bypass CAPTCHA, access controls, or provider rate limits.
- Tests must use disposable emulator state and a separate test database. Never use or mutate a user's live library, account, or preferences. Preserve extension signing/trust rules and disabled-source settings.
- Distinguish PASS, FAIL, INCONCLUSIVE, SKIPPED, and NOT_RUN. `compileDebugAndroidTestKotlin` proves compilation, not behavior; a green workflow with a skipped emulator job is not an executed Android test.
- Investigate failures from logs and artifacts. Do not weaken assertions or turn blocked external requests into PASS. Respect the project's limit of two focused correction cycles per task unless the user explicitly authorizes more.

## Physical-device acceptance

A real-phone manual smoke remains the final acceptance gate for interactive UX, device-specific Reader behavior, activity lifecycle, and navigation. Record the tested APK/commit SHA, device/Android version, exact steps, observed behavior, and any blocker. Passing an emulator test reduces risk but cannot substitute for that manual evidence.

For the generic Add-on compatibility checkpoint, keep the MangaFire real journey, offline Reader/navigation instrumented test, and targeted MangaBall validation as separate evidence. Do not claim an earlier source-only search proves binding, chapter selection, or readability. A previously successful live E2E remains historical evidence unless repeated at the current code SHA.

## Local or externally supplied instructions

This file is the repository's documented Android-test policy. An agent may also receive uncommitted workspace files, environment-level instructions, or an older copied `AGENTS.md`. Inspect those sources before declaring a conflict. If a higher-priority external instruction still forbids emulators, disclose the conflict and ask the user to update that external instruction instead of silently ignoring it. Updating repository files alone cannot modify separately supplied instructions.

No automatic merge, pull request, distribution APK, or unapproved provider requests are implied by this policy.
