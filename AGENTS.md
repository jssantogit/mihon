# Tsuzuki agent instructions

Read these files before changing Tsuzuki code:

1. `docs/TSUZUKI-SPEC.md`
2. `docs/TSUZUKI-DEVELOPMENT.md`
3. the active file under `docs/superpowers/plans/`
4. `.agents/rules/tsuzuki-development.md`
5. `docs/AI-WORKFLOW.md` (Android/AI validation policy)

Core invariants:

- Metadata != Source.
- Source Manga != CanonicalTitle.
- Source Chapter != CanonicalChapter.
- Canonical identity != Provider identity.
- Mihon remains the operational reading mechanism.
- Tsuzuki-owned behavior belongs behind dedicated domain/data boundaries where practical.

Development is CI-first. Local full Gradle verification is optional; GitHub Fast CI is the required task gate.

Use `[ci-full]` only for justified full verification, as defined by `.agents/rules/tsuzuki-development.md`. Use `[apk]` only when an installable artifact or physical-device test is useful. Do not generate APK artifacts for every task.

Do not broaden the active task scope. Do not implement future plan phases early.

## Android validation policy

- **Automated Android emulators are permitted in GitHub Actions** for opt-in, isolated instrumented tests, including deterministic navigation, extension loading, and bounded real-provider E2E when that external probe is explicitly authorized. CI may use ADB against its disposable emulator.
- Do not automate a personal/physical phone by default. Manual testing on a real phone remains required for final user-experience acceptance of Reader/navigation flows; emulator E2E supplements but does not replace it.
- Do not run uncontrolled live-provider sweeps, bypass CAPTCHA/access restrictions, access user data, or silently activate real-provider probes. Respect each workflow's explicit opt-in and privacy constraints.
- Android test compilation, a skipped emulator job, and passing unit tests are **not** evidence of executed Android behavior. Report actual job results and remaining manual acceptance separately.
- If a local, injected, or uncommitted `AGENTS.md` / `docs/AI-WORKFLOW.md` contradicts this policy, reconcile that copy explicitly before continuing; a remote Git update does not override external developer instructions.
