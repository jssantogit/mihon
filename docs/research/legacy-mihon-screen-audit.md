# Legacy Mihon surface audit

**Date:** 2026-10-01  
**Baseline:** `main` at `e83a13b164a6f46404fc42903ca1e6ff580d60e4`  
**Purpose:** identify source-centric Mihon UI/navigation that conflicts with Tsuzuki's canonical-work model.

This is an implementation audit, not a blanket deletion list. Some old screens still host useful utilities and must be replaced or rerouted before their shells can be removed.

## Current intended primary navigation

`HomeScreen.tabs` exposes only:

1. Tsuzuki Home;
2. Tsuzuki Search;
3. unified canonical Library;
4. Tsuzuki Settings.

Any other Mihon tab/screen is therefore either a compatibility route, a hidden utility, or stale product UI.

## P0 — legacy detail route

### `MangaScreen`

**Status:** obsolete as the default work-detail surface.

It is source/manga-ID centric and is the screen shown in the reported Reader bug. The Reader app bar previously emitted `SHORTCUT_MANGA`, which `MainActivity` translated into `HomeScreen.Tab.Library(mangaId)`; `HomeScreen` then pushed `MangaScreen(mangaId)`.

**Current branch action:** fixed the Reader path. Reader state now retains the canonical title identity whenever available, and tapping the Reader title opens `CanonicalTitleScreen`. A legacy `MangaScreen` fallback remains only for genuinely unmapped Reader sessions.

**Remaining known callers to retire/redirect:**

- `MainActivity` `SHORTCUT_MANGA` handling;
- legacy History cover/dialog routes;
- legacy Updates cover routes;
- legacy Global Search result routes;
- legacy Deep Link result routes;
- migration dialogs inside `MangaScreen` itself.

**Required follow-up:** add a reusable Mihon-manga/source → canonical-title resolver so old ID-only entry points can redirect without reopening source-centric details.

## P1 — hidden Mihon tabs still reachable

These tabs are **not** in the visible Tsuzuki bottom navigation but remain routable from legacy actions.

### `HistoryTab`

- Hidden from `HomeScreen.tabs`.
- Still reached by `SHORTCUT_HISTORY` and the launcher shortcut `show_recently_read`.
- Uses legacy history models, legacy Reader intents and `MangaScreen`.

**Direction:** replace with canonical reading history/Continue Reading behavior, then retire the tab and public shortcut.

### `UpdatesTab`

- Hidden from `HomeScreen.tabs`.
- Still reached by `SHORTCUT_UPDATES` and launcher shortcut `show_recently_updated`.
- Opens `MangaScreen` and legacy Reader coordinates.

**Direction:** retain only until a canonical updates surface exists; then retire the tab/shortcut.

### `BrowseTab`

- Hidden from `HomeScreen.tabs`.
- Still reached by `SHORTCUT_SOURCES`, `SHORTCUT_EXTENSIONS` and launcher shortcut `show_catalogues`.
- Contains old Sources, Extensions and source-migration tabs.

Tsuzuki already has **Settings → Add-ons** as the intended extension/add-on entry point.

**Direction:** retire the Browse shell and redirect extension/source management entry points into the Tsuzuki Add-ons/settings flow. Keep the extension-management internals that the new surface still uses.

### `MoreTab`

- Hidden from `HomeScreen.tabs`.
- The Tsuzuki Settings tab already replaces its settings role.
- `SHORTCUT_DOWNLOADS` currently routes through this hidden tab only to push `DownloadQueueScreen`.

**Direction:** route Downloads directly to `DownloadQueueScreen`, then retire the More shell. Preserve useful destinations such as Downloads, Collections/Library tooling and About through current Tsuzuki surfaces.

## P1 — legacy search/detail stack

### `GlobalSearchScreen`

Old source-global search. Results open `MangaScreen` or `BrowseSourceScreen`.

**Direction:** system/global search should hand the query to `TsuzukiSearchTab`. Retire this screen from normal product navigation after query handoff exists.

### `DeepLinkScreen`

The current Android search/share bridge can resolve directly into `MangaScreen`, legacy Reader coordinates, or `GlobalSearchScreen`.

**Direction:** keep the tiny exported `DeepLinkActivity` trampoline if Android integration still needs it, but replace the source-centric `DeepLinkScreen` resolution flow with canonical search/materialization.

### `BrowseSourceScreen`

Source-specific browse UI remains useful as low-level source/extension tooling but conflicts with the normal Tsuzuki product model when exposed as work discovery.

**Direction:** remove from normal user navigation; retain internally only where source configuration/debug/compatibility still requires it.

## P2 — source-migration UI

The following Mihon flows are coupled to a source-defined manga model:

- `MigrationConfigScreen`;
- `MigrateMangaDialog`;
- the Browse migration tab;
- migration actions inside `MangaScreen`.

Tsuzuki's stable-work model is intended to switch/fallback reading providers without migrating the work itself.

**Direction:** treat these as retirement candidates. Verify that no unique data-migration need remains before deleting their implementation.

## P2 — legacy-detail-only features

### `MangaNotesScreen`

Currently belongs to the old `MangaScreen` flow.

**Direction:** decide separately whether notes are a Tsuzuki feature worth porting to canonical works. Do not keep the legacy details screen solely to preserve notes.

### legacy tracking sheet inside `MangaScreen`

Tracking remains a real Tsuzuki feature, but the old detail screen should not be its host.

**Direction:** preserve tracking infrastructure and move any missing per-work controls into canonical Detail before deleting the old host.

## Keep / not legacy-removal candidates

### `ReaderActivity`

Keep. It contains substantial canonical Tsuzuki runtime integration. The bug was its title-navigation target, not the Reader shell itself.

### `DownloadQueueScreen`

Keep as a utility; remove only the hidden `MoreTab` detour.

### `SettingsScreen` and `SettingsMainScreen`

Keep. They now host Tsuzuki-specific Account, General, Integrations, Add-ons, Reading and About surfaces.

### extension/add-on management internals

Keep while they back **Settings → Add-ons**. Retiring `BrowseTab` does not mean deleting extension infrastructure.

### `WebViewActivity` / WebView utilities

Keep for provider/source interaction and diagnostics where required.

### `CategoryScreen`

Not safe to remove yet. The unified canonical Library still invokes it for category editing. Revisit when Collections/categories are fully unified.

## Stale public entry points

`app/src/main/shortcuts.xml` still exposes four launcher shortcuts:

- Library — current;
- Recently Updated — legacy hidden tab;
- Recently Read — legacy hidden tab;
- Browse/catalogues — legacy hidden tab.

The final three should be removed or redirected when their replacement decisions above are implemented.

`MainActivity` also still accepts legacy shortcut actions for Manga, Updates, History, Sources, Extensions and Downloads. These should be retired progressively rather than deleted all at once because notifications/system integrations may still emit them.

## Recommended cleanup order

1. **Reader title → canonical Detail** — implemented in this branch.
2. Introduce one reusable legacy-manga → canonical-title resolver.
3. Redirect all remaining `MangaScreen` entry points.
4. Redirect Downloads directly and Add-ons/source management into current Tsuzuki settings.
5. Replace system/global search with Tsuzuki Search query handoff.
6. Remove stale launcher shortcuts.
7. Replace/retire hidden History and Updates tabs.
8. Retire Browse/More shells.
9. Remove source-migration UI after verifying no remaining unique requirement.
10. Delete `MangaScreen` only after repository-wide caller count reaches zero.

## Guardrail

Do not delete a legacy class merely because it is not visible in the bottom navigation. First remove or redirect every notification, shortcut, deep-link, dialog and compatibility caller. The canonical work/source/provider invariants take precedence over visual cleanup.
