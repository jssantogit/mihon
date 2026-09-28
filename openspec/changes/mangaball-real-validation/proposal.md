# MangaBall real-extension validation

## Why

The generic Add-on branch has deterministic coverage and a historical MangaFire real journey, but MangaBall's 42-source result matrix proves only that search returned candidates. It does not prove identity, binding, chapters, reader options, or pages. The pre-merge decision needs a bounded real-extension experiment without altering production behavior.

## Change

- Add an opt-in Android emulator journey using the pinned MangaBall 1.6.1 APK, one pt-BR internal source (`35546023386335815`), and one reference work with an independently specified source URL.
- Verify extension loading, one Add-on grouping, disabled peer exclusion, progressive search, identity/explicit confirmation, binding persistence, inventory, canonical reconciliation, selector options, reader preparation, and `getPageList` in order.
- Reuse production Tsuzuki adapters/use cases and the disposable E2E database composition. Classify unavailable/ambiguous external behavior as `INCONCLUSIVE`; do not weaken matcher thresholds or report a partial path as PASS.
- Keep the test out of Fast CI and execute it only through explicit workflow dispatch. Add fail-closed sanitized reporting and deterministic verifier tests.

## Out of scope

No production MangaBall adapter, provider mass search, CAPTCHA bypass, site scraping, physical-phone automation, APK distribution, PR, or merge. Position-of-reading UX acceptance remains manual on a phone.

## Evidence baseline

Fixture SHA-256 `c2212a46d201034c15717873183b1b1261a00fbc5db52ab5eae6976f386d6739`, 81,635 bytes, verified locally on 2026-09-25. Historical search result for pt-BR source: run `35944402938` returned nine candidates, but no candidate identity or chapters were proved. The known reference URL for One-Punch Man is `https://mangaball.net/title-detail/one-punch-man-68515501702284f83417844d/`; it is an identity reference, not proof of current chapter availability.
