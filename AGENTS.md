# Tsuzuki agent instructions

Read these files before changing Tsuzuki code:

1. `docs/TSUZUKI-SPEC.md`
2. `docs/TSUZUKI-DEVELOPMENT.md`
3. the active file under `docs/superpowers/plans/`
4. `.agents/rules/tsuzuki-development.md`

Core invariants:

- Metadata != Source.
- Source Manga != CanonicalTitle.
- Source Chapter != CanonicalChapter.
- Canonical identity != Provider identity.
- Mihon remains the operational reading mechanism.
- Tsuzuki-owned behavior belongs behind dedicated domain/data boundaries where practical.

Development is CI-first. Local full Gradle verification is optional; GitHub Fast CI is the required task gate.

Do not broaden the active task scope. Do not implement future plan phases early.
