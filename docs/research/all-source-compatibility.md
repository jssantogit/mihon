# All-source extension compatibility research — 8 exact APKs / 222 internal sources

The first real emulator matrix (run 35941466896) passed 8/8 exact,
user-supplied APKs. Real source inventory: MangaBall 42; AnimeXNovel 1;
MangaFlix 1; Manga Livre.to 1; MangaDex 61; MangaDot 108;
MangaFire 7; Mangas Brasuka 1 = 222. This proves extension loading, **not**
title-search availability, canonical binding, or chapter fallback.

## Generic validation, not one-off MangaBall treatment

The offline instrumentation cross-checks **every** installed internal
source against the Tsuzuki `AddonRepository` enabled snapshot and
`ReadingSourceGateway.listInstalled(language)`. A missing source
is a genuine Tsuzuki integration defect, not a provider outage.

The opt-in live workflow divides 222 sources into four disjoint shards
of 56, 56, 56 and 54, ordered by pinned fixture inventory and then
source ID. Each shard uses a disposable API 35 emulator, verifies real
extension loading, then makes at most one search per source using the
**same Tsuzuki gateway** as the application. One-Punch Man is only
a diagnostic query; EMPTY results for a language/region without the
title do not mean the extension is broken.

Search requests are strictly sequential with 2.5 seconds between
attempts, up to 18 seconds per source (subject to extension-blocking
network calls). HTTP 429 or an explicitly reported CAPTCHA pauses
remaining searches in that extension/shard; no automatic retry,
CAPTCHA solving or HTTP restriction bypass. Shards run sequentially.
HTTP 503, empty results and other provider outcomes are observations,
not a CI infrastructure failure.

Strict per-source result accounting rejects partial or zero-test
green runs. Only allowlisted source IDs, language labels, closed
failure categories, HTTP status, result counts and elapsed times are
exported to short-lived CSV artifacts. Raw responses, URLs, cookies,
headers and stack traces must never enter artifacts.

The separate live job requires an explicit manual GitHub Actions
dispatch or an `[android-live-matrix]` commit marker
on this one test branch. Routine CI and APKs never execute live requests.

## Next stage after observation

For sources yielding plausible results, test exact-title disambiguation,
canonical linking, chapter inventory, chapter variants, alternative
selection, and reader fallback. Never merge canonical titles by
string similarity alone. Investigate app bugs when a healthy real
source does not propagate through Tsuzuki; classify upstream outages,
CAPTCHAs, unsupported languages or absent titles separately.
No merge or user-installable APK before evidence-supported fixes.
