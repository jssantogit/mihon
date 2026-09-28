# Runtime integration test audit — baseline

**Date:** 2026-09-23  
**Branch:** `tsuzuki/runtime-integration-tests`  
**Base:** `tsuzuki/fix-mangafire-binding` @ `2e76abcfa9dd155178cec06783abc043120141ea`

## Findings before implementation

The existing suite has substantial **isolated unit coverage**, but no reusable deterministic
scenario that exercises the Tsuzuki source/runtime boundaries together, and no test that loads a
real Mihon extension APK.

| Area | Existing evidence | Gap at baseline |
| --- | --- | --- |
| Extension/add-on registry | `DefaultAddonRegistryTest`, `MihonAddonRepositoryTest`, `MihonAddonProviderFactory` tests use fake repositories/providers. | No single scenario connects discovered internal sources to actual binding and content resolution. |
| Title search and binding | `ResolveContentBindingTest` uses a fake `ReadingSourceGateway`; `MihonReadingSourceGatewayTest` uses a test `CatalogueSource` and fake `SourceManager`. | Search responses are canned objects/exceptions; no local HTTP response is driven through Mihon's `HttpSource` response/parser path. |
| Internal-source eligibility | Unit tests cover enabled/disabled IDs and languages with fakes. | No repeatable multi-language runtime fixture feeds that state into the entire binding-to-selector flow. |
| Binding materialization/persistence | Gateway, resolver, payload codec, and repository tests assert local effects independently. | No shared persistence fixture proves that the selected source representation remains associated with the canonical title through later inventory and selector queries. |
| Chapter inventory/reconciliation | `MihonChapterInventoryGatewayTest`, `MihonChapterProbeProviderTest`, `ChapterInventoryAndReconciliationTest`, and `RefreshChapterEvidenceTest` cover adapters and domain rules with fake sources/repositories. | No end-to-end fixture combines source response, chapter observations, canonical reconciliation, variants, and content availability. |
| Selector/content | `ResolveChapterContentTest`, `MihonContentProviderTest`, and `ContentSelectorScreenModelTest` cover resolver/UI behavior with fake providers. | No same-scenario test proves which options survive from source discovery through final selector presentation. |
| HTTP failures | Diagnostic tests classify selected throwable shapes. `MockWebServer` is already a test dependency and is used by Supabase tests. | `MockWebServer` is not used for chapter/source lookup. Gateway tests do not reproduce HTTP 200/empty, 403, 429, 5xx, malformed response, connection failure, or response timeout through Mihon's source request path. A generic `HttpException` currently reaches the binding layer as an unstructured failure and can be categorized as an extension failure without its known status. |
| Real extension/runtime | Mihon's `ExtensionManager`/`AndroidSourceManager` load installed extension packages and their source instances in the app. | `app/src/androidTest` does not exist; the repository tracks no MangaFire APK/JAR; no version-1.6.34 digest or fixture is checked in; current CI does not run an emulator or install an extension. The prior MangaFire observations therefore remain device evidence, not reproducible CI evidence. |
| CI planning | `.github/scripts/ci_v2_plan.py` has 29 planner tests; `ci-v2.yml` runs them in the Change Planner job. Baseline command `python3 .github/scripts/test_ci_v2_plan.py` passed 29/29. | Routing is module-local: a Tsuzuki Domain change runs the Domain shard plus App compile, not App tests; a Tsuzuki App gateway change runs App tests, not Domain. No routing test asserts paired coverage for cross-boundary runtime paths. Docs-only planning already selects no Gradle jobs. |

The base's latest observed CI v2.1 run is `35896369795`, completed successfully at the requested base SHA.

## Constraints for the implementation

- Keep canonical title/chapter identity, reading progress, source preference, resolver choices,
  and the temporary diagnostic V2 behavior unchanged.
- Keep synthetic HTTP fixtures loopback-only. Never reach public manga providers from deterministic
  tests or retain page images/HTML, credentials, headers, full URLs, or arbitrary exception text in
  diagnostic output.
- Preserve the original failure as the cause while exposing only closed, structured failure
  categories and validated numeric HTTP status where Mihon provides it. Generic 403 is not CAPTCHA;
  CAPTCHA requires a recognized explicit signal. Cancellation remains cancellation.
- Route runtime integration contracts/gateways/binding/inventory/selector edits to both relevant
  Tsuzuki Domain and App test shards. Docs/UI-only changes must not be escalated to expensive
  release/emulator checks.
- Real MangaFire verification is a separate opt-in lane only if an authorized, immutable artifact
  and its integrity can be established. Without that, report the real-extension test as unavailable;
  do not infer its outcome from the deterministic fixture.
