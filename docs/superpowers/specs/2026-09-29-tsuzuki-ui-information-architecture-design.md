# Tsuzuki UI Information Architecture — Authoritative Spec

Status: authoritative for Front 2 (UI organization)
Baseline: `7d94d3d27f4556782a0ef46312ba4f8d409cee5d`
Scope: information architecture, navigation, structural content presentation, and settings organization. This is not the final visual-design pass.

## Goals

1. Make the current application understandable and testable before the final visual redesign.
2. Give Tsuzuki-owned surfaces a coherent information architecture without rewriting stable domain/runtime behavior.
3. Preserve existing manga reading, canonical identity, source resolution, collections, integrations, sync, downloads, and library behavior unless a change is explicitly specified here.
4. Remove development-oriented navigation from normal user flows.
5. Establish reusable structural content primitives that can receive the final visual language later.

## Non-goals

- Final colors, typography, radii, animation language, or branding polish.
- Replacing the canonical runtime or Mihon extension runtime.
- Rebuilding the Collections domain.
- Generalizing every catalog/provider contract in this front.
- Deciding whether the fourth bottom tab ultimately remains Settings or becomes Profile.
- Changing reader semantics, canonical progress, source fallback, or chapter inventory behavior.

## Primary navigation

The bottom navigation remains structurally:
- Home
- Search
- Library
- Settings

The fourth-tab Profile question remains open and must not block this front.

Search must use a search/magnifying-glass icon rather than the current book icon.

## Search

Search is a content-discovery surface, not a shortcut to technical settings.

- Remove the persistent Integrations action from the Search top bar.
- Preserve a contextual recovery CTA when search cannot operate because no metadata integration/provider is usable.
- Search results and discovery content should use the shared structural title/artwork primitive introduced by this front.
- Do not deepen coupling to the hardcoded TRENDING / POPULAR / RECENTLY_UPDATED enum model. Existing behavior may remain temporarily, but new UI architecture must be provider/catalog-friendly.

## Settings information architecture

Replace the current flat settings index with grouped sections.

### Conta
- Account
- Sync only when its user-facing state is relevant to an authenticated account. Sync must not remain a misleading unconditional root-level destination.

### Conteúdo
- Integrations
- Add-ons
- Tracking / Monitoring where appropriate to the existing capability model

### Leitura
- Reader
- Downloads
- Library settings that govern reading/library behavior

### Aparência
- Appearance
- Personalizações

### Dados e aplicativo
- Data & Storage
- Security
- Advanced
- About

Exact translated labels may follow the repository localization conventions, but the semantic grouping above is authoritative.

### Personalizações

Create `Aparência -> Personalizações` as the home for product-level presentation/configuration features. Collections configuration belongs here.

The legacy root item named `Home & Collections` must be removed. It is not an accurate product concept and must not survive as an alias in the visible Settings IA.

## Integrations

`Settings -> Integrations` is an index of integrations, not the final configuration surface.

- Kitsu and MyAnimeList must be navigable as individual integration destinations.
- Provider-specific controls belong to provider-specific pages.
- The index may show concise status/enabled information.
- The architecture must permit additional metadata providers later without redesigning Settings.
- Existing provider capability/config/auth contracts should be reused rather than replaced.

## Add-ons

Add-ons remain under Settings/Content. Existing install, repository, enable/disable, settings, and uninstall behavior must be preserved. This front may reorganize entry points but must not rewrite the add-on runtime.

## Collections model and navigation

The existing domain hierarchy is authoritative:

`Collection -> Folder -> List -> catalog items`

The UI must stop presenting the entire hierarchy as one expanded tree.

Target navigation:
1. Collections screen: collections.
2. Collection screen: folders belonging to that collection.
3. Folder screen: lists belonging to that folder.
4. List screen: manga/catalog items resolved by that list.

Nested folders already supported by the domain must remain representable. CRUD, query editing, sorting, import/export, tombstones, built-ins, sync semantics, and runtime list state must be preserved.

## Home

Continue Reading remains the first row whenever it has content.

The current projection `Collection -> List -> Manga` is not the target Home model.

Target Home projection:
- Continue Reading
- exposed/configured Collection sections
- each Collection exposes its Folders as the immediate Home children
- opening a Folder reveals its Lists
- opening a List reveals its manga/catalog items

Home must not dump every List and its manga directly under a Collection.

A structural hero region should be supported by the Home composition, but its final styling/content-selection policy belongs to the visual-design/product follow-up unless separately specified.

## Shared title/artwork structure

Introduce/reuse a shared structural presentation primitive for manga/catalog titles. It should be capable of:
- artwork/cover
- title
- compact metadata/context
- click/navigation action
- optional progress/context affordance

Use this structure for Home catalog content, Continue Reading, and Search where appropriate.

This is an anatomy contract, not a final visual-design contract. Do not freeze final dimensions, colors, corner radii, gradients, or typography in this spec.

Continue Reading must display cover artwork when available while preserving canonical chapter navigation, new-chapter count, progress context, and remove-from-continue-reading behavior.

## Diagnostics and development UI

Normal user-facing surfaces must not expose diagnostic/development actions unless they are intentionally housed under Advanced/developer-oriented settings.

Removing a diagnostic entry point must not remove the underlying diagnostic capability needed for testing/support.

## Compatibility constraints

- No local Gradle execution for this front; validation is CI-only.
- Preserve canonical title IDs and source representation semantics.
- Preserve reader and source switching behavior.
- Preserve existing Collections persistence/sync semantics.
- Preserve existing integration/provider contracts unless an implementation task explicitly requires a compatible extension.
- Prefer navigation/presentation changes over domain rewrites.
- New strings should follow localization conventions rather than proliferating hardcoded user-facing text.

## Acceptance criteria

The front is complete when:
1. Bottom Search uses a search icon.
2. Search no longer has a permanent Integrations top-bar action; contextual recovery remains possible.
3. Settings is grouped according to this spec and `Home & Collections` no longer appears as a visible root concept.
4. `Appearance -> Personalizações` exists and exposes Collections configuration.
5. Integrations supports provider-specific destinations for Kitsu and MAL.
6. Collections is navigable by hierarchy instead of one fully expanded tree.
7. Home projects Collections to Folders rather than directly dumping Lists/manga.
8. Continue Reading and catalog/search title presentation can render artwork through shared structural primitives.
9. User-facing diagnostic clutter is removed/re-homed without deleting diagnostic infrastructure.
10. Existing reading, library, add-on, integration, sync, and Collections persistence behavior has regression coverage.
11. Required CI gates are green.
