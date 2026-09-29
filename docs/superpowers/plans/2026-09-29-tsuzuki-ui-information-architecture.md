# Tsuzuki UI Information Architecture — Implementation Plan

Status: authoritative implementation plan
Spec: `docs/superpowers/specs/2026-09-29-tsuzuki-ui-information-architecture-design.md`
Baseline: `7d94d3d27f4556782a0ef46312ba4f8d409cee5d`
Working branch: `tsuzuki/ui-information-architecture`

## Engineering rules

- Follow the authoritative spec; do not silently expand scope into the final visual redesign.
- Do not run Gradle locally. Use repository CI for Gradle/build/test validation.
- Keep commits small enough to diagnose CI regressions.
- Preserve domain/runtime contracts unless a task explicitly requires a compatible extension.
- Add/adjust tests with behavioral changes.
- Do not merge or generate an APK until explicitly requested.

## Wave 0 — Baseline and guardrails

### Task 0.1 — Record baseline
Confirm branch starts exactly from the frozen baseline and document the relevant UI entry points.

### Task 0.2 — Establish regression map
Identify existing tests for Home, Search, Settings navigation, Collections, integrations, and canonical Continue Reading. Add focused non-UI tests only where behavior being changed lacks coverage.

Exit: implementation branch is traceable to baseline and affected contracts are mapped.

## Wave 1 — Settings information architecture

### Task 1.1 — Group Settings index
Refactor `SettingsMainScreen` from a single flat list into semantic sections defined by the spec. Reuse existing destination screens.

### Task 1.2 — Remove legacy Home & Collections root concept
Delete the visible root entry. Do not delete Collections functionality.

### Task 1.3 — Add Appearance -> Personalizações
Create a Personalizações destination under Appearance and place Collections configuration/management entry there. Avoid duplicating the full Collections tree inside Settings.

### Task 1.4 — Account/Sync presentation
Make Sync placement/status consistent with account state without changing sync runtime semantics.

Exit: Settings hierarchy matches the spec and all retained legacy destinations remain reachable in their intended groups.

## Wave 2 — Search and primary-navigation cleanup

### Task 2.1 — Correct Search icon
Replace `ic_book_24dp` in `TsuzukiSearchTab` with the appropriate existing/new search asset.

### Task 2.2 — Remove permanent Integrations action
Remove Integrations from Search top bar and corresponding permanent navigation coupling.

### Task 2.3 — Preserve contextual recovery
When Search is in `NeedsIntegration`, retain a contextual route to configure integrations. Keep it state-specific.

### Task 2.4 — Localization cleanup
Move newly touched hardcoded user-facing strings into the project localization mechanism where practical.

Exit: Search reads as a search surface and still recovers correctly from missing-provider state.

## Wave 3 — Shared content anatomy and Home

### Task 3.1 — Artwork-capable title primitive
Introduce a reusable structural component/model capable of cover, title, metadata/context, click action, and optional progress/context affordance. Reuse existing image loading infrastructure.

### Task 3.2 — Continue Reading artwork
Refactor Continue Reading cards to use the shared anatomy and show cover artwork when available. Preserve canonical chapter intent, progress, new-chapter count, and removal.

### Task 3.3 — Home catalog artwork
Refactor catalog presentation to use the shared anatomy rather than text-only cards.

### Task 3.4 — Home projection
Change Home presentation/query projection from direct Collection -> List rows to Collection -> Folder children. Add navigation events/screens needed to descend Folder -> Lists -> manga without rewriting Collection persistence.

### Task 3.5 — Hero structural slot
Add a structural hero region/contract only if it can be done without inventing a final selection policy or final styling. Otherwise leave an explicit follow-up seam and test the Home ordering contract.

Exit: Continue Reading is first when present, title content can show artwork, and Collections no longer dump list contents directly on Home.

## Wave 4 — Hierarchical Collections navigation

### Task 4.1 — Collections index
Refactor the first Collections screen to show Collection-level entries/actions rather than the whole expanded tree.

### Task 4.2 — Collection destination
Add a destination showing folders for one Collection while preserving collection CRUD.

### Task 4.3 — Folder destination
Add a destination showing child folders/lists as supported by the existing domain. Preserve nested-folder semantics.

### Task 4.4 — List destination
Add a destination that resolves and presents the list's catalog items, with edit/query/runtime-state actions available in an appropriate place.

### Task 4.5 — Preserve transfer/sync semantics
Keep import/export, tombstones, built-ins, and sync behavior intact. Do not create a second Collections persistence model.

Exit: hierarchy is navigable and no normal screen expands the complete Collection/Folder/List tree by default.

## Wave 5 — Integration configuration destinations

### Task 5.1 — Integration index rows
Make Kitsu/MAL rows navigable while retaining concise enabled/status information.

### Task 5.2 — Provider detail contract
Introduce a provider-detail presentation contract that consumes existing capability/config/auth state.

### Task 5.3 — Kitsu detail
Implement the Kitsu-specific configuration destination using existing supported capabilities.

### Task 5.4 — MyAnimeList detail
Implement the MAL-specific configuration destination using existing supported capabilities.

Do not fabricate configuration options not supported by provider contracts.

Exit: each current metadata integration has its own settings destination and future providers can follow the same pattern.

## Wave 6 — Diagnostics cleanup and regression validation

### Task 6.1 — Audit user-facing diagnostic actions
Remove or re-home diagnostic/development-only controls under Advanced where appropriate. Preserve diagnostic implementation.

### Task 6.2 — Navigation regression tests
Cover Settings destinations, Search missing-integration recovery, hierarchical Collections navigation state, and Home ordering/projection at the most stable test layer available.

### Task 6.3 — CI
Push the branch and run required repository CI. Fix format/static/test/build failures through CI evidence only; no local Gradle.

### Task 6.4 — Human smoke checklist
Prepare an APK smoke checklist covering:
- Home ordering and artwork
- Search icon/results/recovery
- Library reachability
- Settings grouping
- Personalizações -> Collections
- Collections hierarchy
- Kitsu/MAL settings
- reader launch from Continue Reading
- add-on/settings reachability

APK generation itself requires explicit user instruction.

## Completion evidence

Before declaring the front complete, report:
- final branch and HEAD
- commits by wave
- changed-file summary
- CI run URL/ID and gate results
- any intentionally deferred visual-design items
- human smoke checklist
