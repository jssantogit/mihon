# Tsuzuki generic Mihon Add-on compatibility

## Decision and baseline

Tsuzuki will reuse Mihon's extension repository, loader, trust, installation, update, and `SourceManager` infrastructure. The repository index is distribution metadata, not proof that a source is executable on this device. `ExtensionStoreService` already decodes `index.pb` using `NetworkExtensionStore` protobuf fields (store metadata and an optional extension list); `ExtensionStoreRepositoryImpl`, `ExtensionApi`, and `ExtensionManager` already connect that catalog to Mihon's normal install flow. No second extension manager, index parser, source registry, or provider-specific adapter is authorized by this design.

Runtime eligibility is the intersection of a trusted installed extension, its loaded `CatalogueSource` implementations, and enabled internal source IDs. One extension package remains one user-visible Add-on. Source ID, source URL, and manga/chapter IDs remain operational identities; canonical title/chapter IDs remain Tsuzuki-owned.

The branch baseline is `6ae00eb4e3cb9348cbdcaacef189671d2f912738`. The opt-in MangaFire 1.6.34/English/One-Punch Man emulator run `36050812440` passed one 14-stage journey through `getPageList`; it does not prove other languages, editions, sites, rendering, or fallback. External-provider tests remain separate from deterministic CI.

## Directed audit

| Boundary | Existing capability | Gap to close |
| --- | --- | --- |
| Repository/index | `ExtensionStoreService` accepts protobuf `index.pb`; Mihon handles repository/install/update/trust. | Confirm repository-format tests and document that index entries are never runtime authority; do not change catalog handling without a reproduced defect. |
| Installed Add-ons | `MihonAddonRepository` groups all loaded source IDs by extension package and removes disabled internal IDs; `DefaultAddonRegistry` exposes enabled providers. | Add lifecycle/eligibility contract tests for mixed enabled state, update/removal, and non-catalogue or untrusted sources. Do not silently enable sources. |
| Search/binding | `MihonReadingSourceGateway` executes real `CatalogueSource` search; `ResolveContentBinding` scores, reuses bindings, and asks for confirmation in ambiguous cases; `ConfirmContentBinding` persists explicit choice. | `executeAll` currently waits for every internal source with `awaitAll` and has no per-source timeout. The link sheet has only a blocking `Searching` state and searches every enabled internal source in the selected Add-on, regardless of language. Partial results and failures need independent presentation without weakening identity gates. |
| Chapter/content | `MihonChapterInventoryGateway`, `MihonContentProvider`, `ResolveChapterContent`, and reader preparation already require binding and chapter identity evidence. | Preserve these boundaries; add contract tests for partial/empty inventory, incompatible chapter variants, disabled/uninstalled sources, and fallback on/off. A search candidate is not a readable chapter. |
| Change Source/selector | `ContentBindingLinkScreenModel`/sheet can choose an Add-on and confirm a candidate; title detail opens the sheet. `ContentSelectorScreenModel` shows real options and failed-provider count. | Show linked/available status and language-aware initial search; expose explicit broader search. Empty/error selector should offer the existing link flow as well as Add-ons settings. The legacy `SourceResolverScreenModel` remains a separate mapping flow; do not create a second binding authority. |
| Verification | `LocalMihonSourceHarness`, MockWebServer tests, and the real MangaFire opt-in journey exist. | Add reusable contract scenarios with at least one independent synthetic extension profile; leave real-provider runs opt-in. |

## Behavioral contract

1. An installed, trusted, enabled extension is discoverable as one Add-on. Its loaded, enabled `CatalogueSource`s are eligible internal search targets. A catalog-only, untrusted, disabled, missing, or incompatible source is not executable evidence.
2. A Change Source search starts from a bounded ordered subset: existing bindings and preferred languages first, then other enabled internal sources only after an explicit “search more” action. It must not scan every installed Add-on in the background. Within the selected Add-on, results and failures are reported per source as they complete; a slow/failing source cannot suppress already verified candidates from another source. Concurrency remains bounded. The Mihon extension API may contain non-interruptible blocking calls, so timeouts bound user-visible waiting but cannot promise to terminate third-party Java code.
3. Candidate previews carry Add-on, internal source, language, title, and source identity. A preview is not a persisted `ContentBinding`. Existing confidence and ambiguity gates remain authoritative; no binding is created solely from equal title text. If multiple editions remain plausible, the user explicitly chooses one.
4. Once confirmed, a binding is materialized, persisted against the existing `canonicalTitleId`, and reused. Search/extension failure is distinct from empty search; an unrelated healthy source still yields its result. Refresh does not delete canonical progress/history or change source preference.
5. A chapter selector offers only `ContentOption`s returned by the existing chapter/content resolver for the selected canonical chapter. If none are linked, it offers a path into the existing binding flow; search results are never displayed as immediately readable options. A successful reader preparation precedes any new preference write, as required by the current reader contract.
6. No feature depends on MangaFire, MangaBall, or MangaDex IDs/names. No catalog fetch installs or executes an extension. CAPTCHA, HTTP 429, and provider restrictions are reported rather than bypassed.

## Acceptance and evidence

- Deterministic tests cover single/multi-source extensions, languages, disabled/untrusted/incompatible state, zero/failing/ambiguous searches, partial progress, binding reuse/persistence, empty/partial/incompatible inventory, selector availability, add-on lifecycle, canonical progress preservation, and fallback on/off.
- A second synthetic provider profile exercises the same contract without MangaFire assumptions. Existing real MangaFire E2E remains a regression, not a Fast CI dependency.
- Each code slice passes the planner-selected Fast CI v2.1 jobs. A final `[ci-full]` checkpoint is justified only if the completed diff crosses domain, adapter, and UI boundaries; no distribution APK or broad external source matrix is required.

## Non-goals and limits

No new extension protocol, no automatic installation/trust, no source-specific patch, no manga merge by title, no fabricated chapters, no production scraping, no rewriting Mihon's reader/downloader, and no guarantee that an external site remains available. The product can promise generic behavior at supported Mihon boundaries, not universal functionality of arbitrary third-party extensions.
