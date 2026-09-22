# Handoff — round 2 chapter metadata study

**Status:** research docs prepared on `tsuzuki/research-chapter-metadata`; no production changes, integration, PR or merge. Review [full report](chapter-metadata-round2.md) and [observations CSV](chapter-metadata-round2-results.csv).

## Answers for the orchestrator

- **Is MangaBaka a viable numeric fallback?** Conditionally as a labeled, non-authoritative fallback. Current policy classifies fields outside `source` as MangaBaka-origin data and licenses its own data CC BY-NC-SA 4.0, requiring attribution, share-alike and non-commercial use. Its field-level derivation, chapter-count definition, and update timestamp are not public. Upstream fields are separately restricted; commercial use or any uncertain redistribution requires written permission. Baka/MU/Kitsu cross-ID concordance is not independent validation.
- **Is there a usable API with a complete editorial inventory?** No tested API. The documented APIs provide count/latest label or upload/release availability, not complete records typed by edition, order, regular/extra/prologue, volume, publication date and revision. Authorized publisher feeds or a validated Tsuzuki-maintained catalog are plausible, but neither was obtained.
- **Which discrepancies were resolved?** Chainsaw Man: official Japanese latest label 232 vs Kitsu's completed 97 (first-part time window). FMA: 150 is the WEBTOON episode sequence; original series is distinct and Kitsu 116 remains unexplained. OPM: VIZ #237 and Tonari #257 are confirmed distinct official page labels, but mapping is unresolved. Berserk #401 has only secondary contemporaneous evidence while API snapshots report 386. Blue Lock #342 is official as of Apr 8, not proven current; 272/362 remain unexplained. Kingdom official #871 (Sep 10) confirms MU 832 is not a reliable current max, but Baka 877/MD 885 remain unexplained.
- **What can be shown confidently?** Exact observed edition-specific official labels with source and date; a catalog’s own most recent reported label if clearly labeled “observed/latest-known”; availability in one reading source; or “unknown”. Do not state a universal count from an aggregate max or provider `latest_chapter`. Exact total requires a validated complete inventory with extras/gaps/edition scope explicit.
- **Which permissions remain open?** Current Kitsu reuse/cache/commercial terms; downstream rights for MU count/release records; whether Baka `total_chapters` can be redistributed/cached offline under CC BY-NC-SA for this app and implications of monetization; attribution/cache/removal obligations for MangaDex; whether any publisher will license episode-index feeds. AniList terms restrict hoarding/competing tracker use; MAL and NDL require provider-specific review. No contact has been sent.
- **Smallest architecture for the desired experience?** Keep Tsuzuki UUID as canonical title identity, model edition/publication separately from source availability, and persist only an authorized, provenance-bound chapter inventory (literal label, order, type, volume/part, date, revision, confidence, confirmation). Availability is a separate per-reading-source list. If no licensed inventory exists, the honest minimum is last-known label/available chapter list—not an asserted complete editorial catalog.

## Evidence snapshot

Direct HTTP query count: 48 attempts — 24 provider detail GETs (HTTP 200), 18 focused MangaDex aggregate GETs (HTTP 200), and six exploratory MangaDex chapter-list GETs (HTTP 400 from incompatible query parameters, discarded). Values are in CSV with IDs, endpoints, dates and scopes. The four provider values for the focus works demonstrate that concordance is not proof of independent origin. Primary official pages support VIZ OPM #237, Tonari OPM #257, WEBTOON FMA Episode 150, Shonen Jump+ Chainsaw Man #232, Magazine Pocket Blue Lock #342 (dated 2026-04-08), and Shonen Jump+ Kingdom #871 (2026-09-10). The Berserk exact #401 remains unverified from a primary enumerated chapter page.

## Pending decisions / stop conditions

1. Determine whether product MVP promises an editorial inventory or just source-specific reading availability.
2. Get provider/legal answers before persistent cache, bulk collection, offline redistribution, monetization, or upstream data migration.
3. Decide if manually curated records will be licensed/editorially sourced and how corrections/review are staffed.
4. Preserve gaps and alternate labels; no fabricated sequential chapters or “highest API number = count”.

## Git handoff

Three intended artifacts only: `chapter-metadata-round2.md`, `chapter-metadata-round2-results.csv`, `chapter-metadata-round2-handoff.md`. No build/test is relevant to research-only documentation; no CI success is claimed. Commit SHA is reported by the root session after commit.
