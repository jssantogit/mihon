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

**Status:** retired and deleted.

It was source/manga-ID centric and caused the reported Reader regression. Canonical Reader sessions now open `CanonicalTitleScreen`. The remaining legacy `SHORTCUT_MANGA` compatibility action no longer opens a source-specific detail page; it lands safely in the unified Library.

The old route, its `MangaViewModel`, the presentation `MangaScreen`, and the legacy manga-notes screen were removed.

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

**Status:** retired and deleted.

Source-specific work discovery conflicts with Tsuzuki's stable-work/provider model. Add-on configuration remains available through Settings → Add-ons and extension details/preferences; the old source-browse route and view model are no longer required.

## P2 — source-migration UI

The following Mihon flows are coupled to a source-defined manga model:

- `MigrationConfigScreen`;
- `MigrateMangaDialog`;
- the Browse migration tab;
- migration actions inside `MangaScreen`.

Tsuzuki's stable-work model is intended to switch/fallback reading providers without migrating the work itself.

**Status:** retired and deleted. The source-migration UI package and Mihon migration feature UI were removed after their remaining callers were eliminated.

## P2 — legacy-detail-only features

### `MangaNotesScreen`

**Status:** retired with `MangaScreen`.

The source-manga notes editor is not kept as a reason to preserve legacy details. If notes return later, they must be designed against canonical works rather than resurrecting this surface.

### legacy tracking sheet inside `MangaScreen`

The host screen is deleted. Tracking infrastructure remains a separate Tsuzuki capability; any future per-work controls belong in canonical Detail rather than a source-centric details screen.

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

`app/src/main/shortcuts.xml` now exposes only the Library shortcut.

`MainActivity` still accepts selected legacy actions as compatibility shims, but none of them revive retired Mihon screens:

- Manga, Updates and History → unified Library;
- Sources and Extensions → Settings → Add-ons;
- Downloads → `DownloadQueueScreen`;
- Android/system search → Tsuzuki Search.

## Cleanup outcome

The planned cleanup sequence is implemented on the stacked cleanup branch:

1. Reader title → canonical Detail;
2. legacy system/search actions → current Tsuzuki surfaces;
3. stale launcher shortcuts removed;
4. hidden History/Updates/Browse/More shells removed;
5. old global/deep-link search UI removed;
6. source-migration UI removed;
7. `BrowseSourceScreen` removed;
8. `MangaScreen` and its view model/presentation host removed;
9. legacy manga-notes UI removed;
10. identity guards prevent these retired surfaces from silently returning.

## Guardrail

Do not delete a legacy class merely because it is not visible in the bottom navigation. First remove or redirect every notification, shortcut, deep-link, dialog and compatibility caller. The canonical work/source/provider invariants take precedence over visual cleanup.


## Validation for this branch

This branch changes only the Reader → details navigation plus the audit/tests. It does not remove hidden legacy tabs yet.

- no local Gradle;
- full GitHub Actions CI required;
- post-CI smoke: open a canonical chapter, tap the Reader title, verify canonical Tsuzuki Detail opens instead of Mihon's `MangaScreen`;
- compatibility smoke: a genuinely unmapped legacy Reader session must not resurrect Mihon details; its legacy title action falls back to the unified Library.


## Cleanup wave implemented after the audit

A stacked cleanup branch removes the retired surfaces that no longer serve Tsuzuki and redirects public compatibility callers before deletion.

### Deleted

- `HistoryTab` — hidden Mihon history tab shell;
- `UpdatesTab` — hidden Mihon updates tab shell;
- `BrowseTab` — hidden Mihon Sources/Extensions/Migration tab shell;
- `MoreTab` and `MoreScreen` — replaced by the Tsuzuki Settings tab and direct utility routes;
- `DeepLinkScreen` and `DeepLinkViewModel` — source-centric Android search/share resolver;
- `GlobalSearchScreen` and `GlobalSearchViewModel` — source-global search surface superseded by Tsuzuki Search;
- the complete legacy source-migration UI stack;
- `BrowseSourceScreen` and its view model/presentation host;
- `MangaScreen`, `MangaViewModel` and the old presentation details host;
- the legacy manga-notes screen;
- launcher shortcuts for Recently Updated, Recently Read and Browse/catalogues;
- shortcut-only drawable resources for those removed launcher entries.

### Redirected compatibility entry points

Old actions are retained only so existing notifications/system callers do not crash:

- legacy **Manga**, **Updates** and **History** actions now land in the unified Library;
- legacy **Sources** and **Extensions** actions now open **Settings → Add-ons**;
- legacy **Downloads** opens `DownloadQueueScreen` directly, without a hidden More tab;
- Android search/share and the old internal search action now hand the query to **Tsuzuki Search**.

The visible launcher shortcut set is now Library-only.

### Deliberately retained

- Reader/runtime infrastructure;
- direct Downloads utility;
- current Settings surfaces;
- Add-on/extension installation, repository and preference infrastructure;
- WebView/source transport utilities;
- `CategoryScreen` while the canonical Library still invokes it;
- non-UI history/update data services where they remain useful to current behavior.

No source-centric Mihon work-detail, browse, migration, history, updates, global-search or More shell remains as a user-facing route.

### Regression guard

The identity contract now fails if any retired route file or retired launcher shortcut is reintroduced. This prevents a later upstream sync from silently restoring the removed Mihon shells.

### Cleanup-wave validation

- no local Gradle;
- full GitHub Actions CI required;
- verify Android launcher exposes only the Library shortcut;
- verify old search/share intents open Tsuzuki Search;
- verify old Sources/Extensions intents open Settings → Add-ons;
- verify Downloads opens directly;
- verify no hidden History/Updates/Browse/More tab can be navigated to;
- verify `SHORTCUT_MANGA` does not open a legacy details screen;
- verify no source-browse or Mihon manga-details route is reachable.
