# Automatic reading sources — product/architecture delta
Date: 2026-09-25
Feature branch: `tsuzuki/automatic-reading-sources`
Base: `tsuzuki/fast-reading-discovery` @ `6363bbcb1e7cd2432fbdce32d3ede3e958186aa8`

## Human-smoke evidence, not assumptions
- Kimetsu no Yaiba: initial two-Add-on/four-source automatic pass ended empty. After the user manually linked other Add-ons, Manga Livre.to pt-BR returned 205 chapter inventory rows in 124ms (repeated 122ms); MangaFlix pt-BR returned INDETERMINATE_FAILURE in 13ms (repeated 13ms), although the selector later held one cached option from each. The latter is not evidence of page availability. Chapter detail still displayed one provisional cached row and 206 inferred slots; the full refresh reported an extension error after Manga Livre.to had returned chapters. Its exact downstream exception requires a stack trace; do not blame MangaFlix without proof.
- Death Note: MangaDot has successfully materialized page one of chapter 1 in the Reader (page counter 1/47). On the new APK a full all-language refresh probed 898 MangaDot rows in about 4.37s, reconciled 888 mapped and 10 provisional, then returned 20 chapter-specific selector options. These counts are not unique verified canonical chapters. MangaDex EN inventory in the supplied trace was empty and dozens of unrelated bound internal language IDs were still probed during explicit diagnostics.

## Production UX contract
Open title using local title/cached chapter state immediately. On chapter selection, publish existing verified options immediately. If absent, discover available reading editions automatically across **all enabled installed Add-ons relevant to configured languages, subject to a bounded first-wave resource budget**. Users should see options from *different providers* accumulate progressively, choose one and read; no normal 'Search more', 'Find reading Add-on', 'Choose a reading Add-on', or manual link action in the chapter selector. Developer-only controls remain available during chapter diagnostics. A genuinely ambiguous edition is the exception: explicit identity confirmation is required, not an automatic binding by name.

## Bounded automatic first wave
Retain the former prioritized initial two Add-ons but **continue automatically** to other installed, enabled, language-compatible packages when the first Add-ons have no readable chapter. Plan up to eight Add-ons and sixteen eligible internal source IDs across the first wave, max two newly queried IDs for each additional package, max three concurrent Add-on searches and two targeted chapter-inventory refreshes. Prefer user title preference; favor pt-BR, pt and EN according to configured language rules. Never enumerate every one of MangaDot's dozens of unrelated language IDs. Search failures, empty inventory, 429, timeouts and ambiguous matches must not block healthy peers or create false absence assertions.

The first verified chapter option appears immediately; later verified options update the **same** selector and preserve the current selection. Stop visible waiting after 10s without making blocking Java providers a hard-kill promise. If no verified option exists by the deadline, offer ordinary retry and an honest temporary-unavailability message. Normal readers should never have to pick an Add-on as a remedy. No automatic installation, trust/enable change or title merge.

## No title-open inventory storm
On opening title, only cached chapters and a nonblocking metadata refresh run. Do not launch the all-Add-on inventory refresh behind every title open: it competes with discovery and re-probes unrelated persisted languages. The explicit developer Refresh action retains comprehensive diagnostics. A newly verified targeted binding reconciles its own chapter evidence and signals the detail screen to reload persisted chapter rows once, without launching full title-wide inventory.

## Acceptance / remaining limitations
- Fake six-Add-on Kimetsu scenario: initial AnimeXNovel empty and MangaFlix failure do not prevent automatic Manga Livre.to discovery; later source delivers a real chapter-specific option.
- Fake multilingual Death Note: MangoDot EN remains considered when giant Add-ons have many unrelated internal source IDs, without scanning them.
- First verified option does not wait for the other sources. Two healthy Add-ons produce two distinct selectable options; disabled/unrelated IDs excluded and no duplicate source queries per scoped pass.
- No manual source-search affordance appears in the normal selector; ambiguity can request edition confirmation. The diagnostic link controls remain opt-in.
- Cached details render before any extension network work; normal initial open does not probe all linked Add-ons. Explicit refresh does.
- Preserve canonical-title/chapter identity and page-position contracts. An inferred chapter, catalogue search hit or cached option cannot be reported as rendered pages until actual Reader page preparation.
- CI test suites validate deterministic behavior, **not** external provider performance. Measure cold/warm chapter selection and page rendering on an approved SHA-pinned APK before claims about latency.
- The eight-Add-on/sixteen-source first wave is a resource budget, not a promise to cover arbitrary hundreds of installed packages. Persisted discovery state, title-specific provider success scoring, a later automatically scheduled wave for large installs, noncooperative worker isolation and a dedicated ambiguity-confirmation UI are subsequent engineering slices. Do not hide these limitations or claim the full UX is proven from unit tests.

No merge or new APK until the user explicitly approves.
