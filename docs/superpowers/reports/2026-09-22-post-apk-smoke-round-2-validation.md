# Tsuzuki — Round 2 post-APK validation handoff

**Baseline:** `tsuzuki/bootstrap` at `caa0f6421d625e024b307ff3af8e17c936e5c3e1`  
**Working branch:** `tsuzuki/post-apk-smoke-round-2`  
**Architecture:** `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`  
**Implementation plan:** `docs/superpowers/plans/2026-09-22-tsuzuki-post-apk-smoke-round-2.md`

## Implemented for code verification

- Safe staged Reader source changes: validate replacement pages before publishing a session; preserve the previous session and canonical reading progress on failure; guard overlapping selections.
- Explicit active Add-on/language label; independent per-title Add-on and language preference confirmations after successful loads. Migration `32.sqm` and content-preference sync support.
- Chapter 0 before 0.5; show Kitsu/MAL reported counts separately from actual chapter identities.
- Bounded chapter discovery, provider fanout and download checks, shared TTL inventory cache, and in-flight invalidation safety.
- On-demand exact chapter matching in already trusted/verified alternative title bindings, without creating canonical chapter rows from metadata or overriding the global automatic-fallback setting.
- Distinguish provider/network failure from a genuine empty inventory; keep valid options when another source fails.
- Recorded chapter ranges grouped by Add-on in title details, with a notice that stored observations are not guaranteed to be currently available.
- `TsuzukiPerf` timing logs for initial cached title display, metadata/chapter refresh and live source inventory fetch.

## Evidence / release gate

- Reduced CI was green at `f0952d5d` (run 35766429465), `7ab96e52` (run 35769750097) and `c1ca96b5` (run 35770246097).
- This report accompanies the consolidated full CI trigger. Inspect **all** full-CI shards, SQLDelight migrations, native-package gate and release compile before proposing a merge.
- Never merge to `tsuzuki/bootstrap` or dispatch the APK workflow on the basis of reduced CI. An Android build/compile check in full CI is not a published test APK.
- A green CI verifies code contracts, **not** live MangaFire content or device image rendering.

## Required human smoke — after explicit APK authorization

1. **Dandadan EN → PT-BR → EN:** keep pages loaded, change source repeatedly, check active label and per-title language preference dialog; simulate an unavailable selection and verify the previous pages and reading progress remain intact, including after reopening.
2. **Dandadan EN image integrity:** capture the exact failing chapter/page. Compare the fetched original image's MIME, dimensions and contents with the Reader display in pager and webtoon modes, with and without border cropping. Do not assume a viewer bug without intact original bytes.
3. **One Punch-Man:** confirm whether the currently enabled MangaFire catalog really starts at 138; check the Add-on-specific recorded range and try another installed/linked Add-on for earlier chapters. Without a confirmed earlier source, do not create chapters 1–137.
4. **Death Note:** compare the Kitsu-reported count (previously 108) with actual Add-on evidence and title bindings. If no Add-on matches unambiguously, no fabricated chapter list may appear.
5. **Hunter x Hunter:** verify that 0, 0.5, 1 and 1.5 render in numerical order without losing reading progress.
6. **Cold/warm performance:** open Dandadan and Nanatsu no Taizai on a fresh launch and then again. Compare `TsuzukiPerf` cached-display, metadata, chapter-inventory network and total refresh times; record installed Add-ons and connectivity.
7. **Reader recovery:** confirm missing chapters and provider timeouts show retryable errors and do not replace working page sessions or silently change preferred Add-ons.

**Open evidence requirements:** raw Dandadan EN page comparison, One Punch-Man real installed Add-on coverage, Death Note actual binding results and device cold/warm timings. These are explicitly unresolved until human smoke.
