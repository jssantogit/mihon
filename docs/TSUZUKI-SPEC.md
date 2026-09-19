# Tsuzuki Product and Architecture Specification

**Status:** Approved design baseline  
**Date:** 2026-09-13  
**Repository:** `jssantogit/mihon`  
**Upstream:** `mihonapp/mihon`

## 1. Purpose

Tsuzuki is a direct fork of Mihon that keeps Mihon as the reading mechanism while replacing the product model around discovery, identity, organization, source selection, chapter organization, and private synchronization.

The product goal is a manga experience closer to a unified media library than a source browser:

> **Mihon remains the mechanism. Tsuzuki becomes the experience.**

Tsuzuki must continue to benefit from Mihon's reader, downloader, extension ecosystem, source execution, networking, WebView authentication, background work, local content support, and related infrastructure. New Tsuzuki behavior should therefore live in a separate domain layer connected to Mihon through adapters whenever practical.

A second principle is equally important:

> **Tsuzuki owns identity; providers and reading sources provide evidence and content.**

This specification defines the architecture, domain model, product behavior, milestone boundaries, failure behavior, migration strategy, and invariants that implementation must preserve.

---

## 2. Core architectural invariants

The following distinctions are fundamental:

```text
Metadata != Source
Source Manga != CanonicalTitle
Source Chapter != CanonicalChapter

CatalogProvider != Reading Source
Library != Tracker
Sync != Backup
Canonical identity != Provider identity
```

Implementation must preserve these invariants even when a shortcut would make a local feature easier.

Additional non-negotiable rules:

1. A provider-specific ID must never become Tsuzuki's primary identity for a work.
2. A reading source entry must never be treated as the canonical identity of a work.
3. A source chapter must never silently redefine canonical chapter identity.
4. A `CatalogProvider` must never become a reading source abstraction.
5. Collections must not encode provider-specific filter models in the core domain.
6. Cloud synchronization must never be required to read content already available locally.
7. A title without catalog metadata must remain usable and readable.
8. Low-confidence identity matches must never trigger destructive automatic merges.
9. Low-confidence source matches must never be accepted silently.
10. Resolver uncertainty must be surfaced rather than hidden through fabricated certainty.
11. Derived and recomputable data must not be treated as irreplaceable user state.
12. Local user actions must update local state before any network synchronization.
13. A provider outage must degrade only the features that depend on that provider.
14. Existing Mihon data must never become inaccessible because canonical enrichment failed.
15. Upstream compatibility is an architectural requirement, not an afterthought.

---

## 3. Architectural approach

Tsuzuki uses a separate domain layer above Mihon rather than replacing Mihon's core domain model.

```text
                         TSUZUKI DOMAIN

        CatalogItem                  CanonicalTitle
             |                            |
             |                 +----------+----------+
             |                 |          |          |
             |              Library    Tracking   Chapters
             |                                      |
      CatalogProviders                         ChapterResolver
             |                                      |
             +----------------+          +----------+
                              |          |
                         ADAPTER LAYER
                              |
             +----------------+-------------------+
             |                |                   |
         Mihon Source     Mihon Reader       Mihon Downloader
             |
      Extensions / Network / Chapters
```

Tsuzuki-owned concepts include, at minimum:

```text
CanonicalTitle
CatalogItem
LibraryEntry
ExternalIdentity
MetadataProvenance
SourceTitleMapping
CanonicalChapter
ChapterVariant
ChapterOverride
Collection
Folder
List
QueryExpression
```

Mihon-owned operational entities remain intact wherever possible. Tsuzuki adapts to them rather than rewriting them.

When a feature could be implemented either by adding a Tsuzuki-owned layer or by deeply modifying Mihon, prefer the Tsuzuki-owned layer unless a concrete technical reason justifies divergence.

---

## 4. Product navigation and mental model

The primary product model is title-centric rather than source-centric.

Conceptual primary navigation:

```text
Home
Discover
Library
Search
```

Sources, extensions, downloads, trackers, and settings remain available, but they are infrastructure and management surfaces rather than the main browsing model.

The user-facing flow should tend toward:

```text
Title
  -> Read
```

rather than:

```text
Source
  -> Manga
      -> Chapters
```

Internally, reading still resolves through Mihon:

```text
CanonicalTitle
    -> preferred language
    -> preferred reading sources
    -> SourceTitleMapping
    -> CanonicalChapter
    -> ChapterVariant
    -> Mihon Reader
```

---

## 5. Canonical identity

### 5.1 Tsuzuki-owned IDs

Every persisted work uses a Tsuzuki-owned stable ID, such as a UUID.

```text
CanonicalTitle
|- id: Tsuzuki UUID
|- identityState
|- effectiveMetadata
|- externalIds[]
|- metadataProvenance[]
|- relations[]
|- createdAt
└- updatedAt
```

Provider IDs such as Kitsu, MyAnimeList, MangaDex, MangaUpdates, or future provider IDs are mappings attached to the title. They are never the primary key.

### 5.2 CatalogItem versus CanonicalTitle

Remote catalog results are ephemeral.

```text
CatalogItem
|- provider
|- providerId
|- metadata
└- externalIds
```

A `CatalogItem` should not automatically create a persisted title simply because it appeared in Search, Discover, Trending, or another feed.

A `CanonicalTitle` is created when there is a reason to persist user state, including actions such as:

- adding the title to Library;
- beginning reading;
- creating a source mapping;
- importing or migrating an existing Mihon library entry;
- creating other durable title-specific user state.

This avoids turning the local database into a partial mirror of remote catalogs.

### 5.3 Identity states

A canonical title may be fully enriched or only partially known.

Recommended conceptual states:

```text
RESOLVED
PARTIALLY_RESOLVED
SOURCE_ONLY
```

A source-only title is valid. Catalog recognition is enrichment, not a prerequisite for existence.

### 5.4 Source-born titles

Content that exists only in Mihon reading sources, local files, obscure catalogs, new releases, doujinshi, or other unsupported metadata providers may still create a `CanonicalTitle`.

Example:

```text
Mihon Source result
      |
      v
CanonicalTitle UUID
|- identityState = SOURCE_ONLY
|- provisional metadata from source
└- SourceTitleMapping
```

If catalog metadata is later discovered, it enriches the same Tsuzuki identity instead of replacing it.

### 5.5 Metadata provenance

No single catalog provider owns the canonical metadata object.

Metadata resolution is field-oriented and provenance-aware.

```text
EffectiveMetadata

title
  value = "Berserk"
  provenance = Kitsu

authors
  values = [...]
  provenance = MangaUpdates

tags
  values = [...]
  provenance = Kitsu + MangaDex
```

The effective value may change as better evidence becomes available, but the title identity does not.

### 5.6 Deduplication and merge

Identity resolution may use evidence such as:

```text
external IDs
normalized titles
aliases
authors
publication information
relations
provider cross-links
```

High-confidence duplicates may be merged automatically.

If two Tsuzuki IDs already exist, one becomes the surviving canonical identity and the other must remain resolvable through an internal alias/redirection mechanism so old references do not break.

Low-confidence duplicates must be surfaced for user confirmation.

A merge may consolidate:

- external IDs;
- source mappings;
- library state;
- chapter mappings and overrides;
- metadata evidence;
- relations.

A low-confidence match must never perform a destructive merge.

### 5.7 Editions and adaptations

Adaptations are separate canonical titles linked through relations.

```text
Re:Zero light novel
   -> adaptation
Re:Zero manga
```

Reissues or editions that represent substantially the same work may remain variants/editions of one title when their chapter structure is compatible.

If an edition significantly changes chapter structure, content, or serialization, it may become a distinct `CanonicalTitle`.

The priority is to avoid combining structurally incompatible works merely because their names are similar.

---

## 6. Catalog architecture

### 6.1 Provider abstraction

Remote discovery and metadata operate through a provider abstraction such as `CatalogProvider` or `MetadataProvider`.

The Tsuzuki domain must not expose provider-specific query/filter models directly.

Initial provider direction:

- **Kitsu:** primary initial discovery/catalog provider.
- **MangaDex:** complementary catalog/tag/external-ID source where useful, while remaining distinct from its role as a possible reading source through Mihon extensions.
- **MangaUpdates:** enrichment for manga-specific metadata where useful.
- **MyAnimeList:** tracker plus complementary identity/rating/ranking data where supported.
- **MangaBaka:** future identity/enrichment candidate.
- **AniList:** not an initial dependency of the Tsuzuki catalog architecture.

The architecture must permit providers to be added, removed, or demoted without changing Tsuzuki canonical identity.

### 6.2 Provider failure

A provider outage must affect only features that depend on fresh data from that provider.

For example:

```text
Kitsu unavailable
|- local Library still works
|- Reader still works
|- downloads still work
|- local progress still works
|- cached metadata may still render
└- Kitsu-backed discovery may show cached/unavailable state
```

No catalog provider may become a single point of failure for reading.

### 6.3 Search

Primary Search is canonical/title-centric.

```text
"berserk"
    |
    v
Berserk
```

It must not primarily render duplicate groups by reading source.

Catalog providers are queried first. If results are absent, sparse, or low relevance, Tsuzuki may offer or discreetly begin a secondary search over preferred reading sources.

Source search must not fan out to every installed source on every keystroke.

### 6.4 Discover

Discover works through the generic query layer rather than directly binding the UI to Kitsu.

```text
Discover UI
    |
    v
Tsuzuki Query
    |
    v
Query Planner
    |
    v
CatalogProvider
    |
    v
CatalogItem[]
```

Unsupported filters must never be silently ignored. The planner may either:

- compile supported predicates to the provider;
- apply safe residual filtering locally;
- reject or surface unsupported semantics when correctness cannot be preserved.

### 6.5 Scores

Provider-specific scores remain provider-specific.

Example:

```text
Kitsu          91%
MyAnimeList    9.47
MangaUpdates   9.0
```

The MVP must not invent a synthetic Tsuzuki average.

### 6.6 Recommendations

MVP recommendations remain deterministic/provider-based.

Permitted initial inputs include:

- provider recommendations;
- related works;
- shared genres/tags/categories;
- same author/artist;
- similar metadata.

AI recommendations, embeddings, and a backend recommendation service are post-MVP concerns.

---

## 7. Library

### 7.1 LibraryEntry

Library membership is distinct from canonical identity.

```text
LibraryEntry
|- canonicalTitleId
|- status
|- favorite
|- addedAt
|- updatedAt
|- categories[]
└- userMetadata
```

A title may exist canonically without currently belonging to the Library.

### 7.2 Library status

Initial status model:

```text
Reading
Planning
Completed
On Hold
Dropped
```

Library state is not the same thing as tracker state, even though status/progress/score may synchronize with connected trackers.

### 7.3 Source independence

A title does not need a resolved reading source to enter the Library.

Adding to Library must not block on source discovery.

A source is resolved only when an action actually requires one, such as:

- reading;
- downloading;
- checking available chapters;
- another explicitly source-dependent action.

### 7.4 Smart Views

The Library may expose smart views using the same broad query-expression concepts as Collections, but over local library data.

Example:

```text
status = READING
AND unreadChapters > 0
```

Collections and Library remain separate product concepts:

```text
Collections -> discovery and catalog organization
Library     -> personal saved state
```

---

## 8. Reading sources and Source Resolver

### 8.1 Mapping model

A canonical title may map to one or more Mihon source entries.

```text
SourceTitleMapping
|- canonicalTitleId
|- mihonMangaId
|- sourceId
|- sourceKey/url
|- language
|- matchConfidence
|- verifiedByUser
|- availabilityState
└- preferredOverride
```

Mihon's operational manga/source data remains intact.

### 8.2 Preferred sources

Reading source preferences are ordered by language.

Example:

```text
English
1. MangaDex
2. Comick
3. MangaFire

Portuguese
1. Source A
2. Source B
```

A title may override the language-level preference.

More granular preference matrices such as language + publication type are deferred unless future evidence justifies the added complexity.

### 8.3 Resolution behavior

The resolver should inspect a small ordered set of preferred sources rather than scanning all installed extensions automatically.

High-confidence matches may be accepted automatically.

Ambiguous matches require user confirmation.

The mapping is then persisted and reused.

An explicit "check more sources" action may broaden the search on demand.

### 8.4 Extension setup

Tsuzuki may recommend and facilitate source setup, but installation/activation remains an explicit user action.

It must not silently install extensions.

### 8.5 Source maintenance instead of migration-centric UX

The old Mihon migration concept remains useful as implementation infrastructure, but user-facing Tsuzuki terminology should center on:

```text
Change reading source
Find alternatives
Repair mapping
Replace source
Bulk source replacement
```

Changing a reading source does not migrate the canonical title, metadata, library identity, or tracking identity.

---

## 9. Canonical Chapter Engine

Chapter organization is a first-class Tsuzuki subsystem.

The governing rule is:

> **A source chapter is not a canonical chapter.**

### 9.1 CanonicalChapter

Conceptual model:

```text
CanonicalChapter
|- id
|- canonicalTitleId
|- displayNumber
|- sortKey
|- volume
|- title
|- type
|- part
|- confidence
└- mappings[]
```

`displayNumber` must not be represented only as a floating-point number because chapter labels may include forms such as:

```text
12
12.5
12a
Extra 3
Prologue
24 Part 2
```

Display representation and ordering semantics must remain separate.

### 9.2 Chapter types

Initial canonical types:

```text
REGULAR
PROLOGUE
EPILOGUE
EXTRA
SPECIAL
ONESHOT
UNKNOWN
```

### 9.3 ChapterVariant

Multiple releases of the same logical chapter are variants, not separate progress identities.

```text
ChapterVariant
|- canonicalChapterId
|- sourceChapterId
|- sourceId
|- language
|- scanlationGroup
|- version
|- releaseDate
└- rawSourceMetadata
```

Example:

```text
Canonical Chapter 45
|- MangaDex / Group A / EN
|- MangaDex / Group B / EN
|- MangaFire / EN
└- Local / PT-BR
```

Progress belongs to the canonical chapter.

### 9.4 Raw source data preservation

The resolver may interpret source numbering but must preserve the source's raw values.

Example:

```text
Raw source:
"Ch. 037.2 - Battle Ends"

Resolved:
canonical number = 37
part = 2
```

The raw source label/title must remain available.

### 9.5 Reconciliation strategy

Canonical chapter structure is derived conservatively from multiple forms of evidence:

```text
catalog metadata
editorial metadata when available
source chapter inventories
known mappings
chapter types
manual overrides
```

No single source or metadata provider automatically defines truth.

When evidence is insufficient, Tsuzuki must communicate uncertainty rather than fabricate an exact chapter count or structure.

### 9.6 Coverage

The engine may compute reading-source coverage against the canonical structure.

Example:

```text
Canonical regular chapters: 150

MangaDex
147 / 150
98%

MangaFire
150 / 150
100%
```

Extras and specials should not inflate the regular-chapter denominator when they have been identified correctly.

If the structure itself is uncertain, coverage must be labeled uncertain.

### 9.7 Gap detection

The engine should detect missing canonical chapters in a preferred source.

Example:

```text
72  available
73  missing on preferred source
74  available
```

Alternative preferred sources may then be queried for the missing chapter.

### 9.8 Fallback reading

The first time a title requires fallback, ask before changing source automatically.

Example:

```text
Chapter 73 is not available from MangaDex.
MangaFire has this chapter.

[Read once]
[Always use automatic fallback for this title]
[Cancel]
```

If the user enables title-level automatic fallback, later gaps may use an alternative source without repeated confirmation.

Fallback is per chapter. Reading one missing chapter from another source does not require replacing the preferred source for the entire title.

### 9.9 Variant selection

When several variants exist, the resolver should apply configurable preferences with sensible defaults, conceptually:

```text
1. preferred language
2. preferred source
3. preferred scanlation group, if configured
4. quality/completeness signal
5. release recency
```

Per-title overrides are allowed.

### 9.10 Manual repair

Automatic resolution is the normal path, but advanced users must be able to repair unusual mappings.

A repair tool may support actions such as:

```text
mark as Extra/Special/etc.
merge or associate parts
map source chapter to canonical chapter
mark a chapter as missing
undo an automatic mapping
```

Manual overrides are durable user state.

### 9.11 Local content

Local manga participates in the same canonical model.

A local title may be source-only and later receive external metadata.

Local chapters may become `ChapterVariant` entries just like extension-provided chapters.

---

## 10. Library updater and chapter refresh

The updater should query the preferred reading source first.

Alternative sources are consulted when there is a reason, including:

- a detected gap;
- preferred source unavailable;
- preferred source appears stale relative to known metadata;
- canonical structure indicates missing chapters;
- user explicitly requests a coverage check.

The updater must not query every preferred source for every library title on every cycle.

This balances completeness with latency, bandwidth, provider load, and rate-limit risk.

---

## 11. Progress, history, and trackers

### 11.1 Canonical progress

Progress belongs to the canonical chapter.

If chapter 73 is read through a fallback source, chapter 73 is read regardless of whether the preferred source later gains its own variant.

History may still record which source/variant was actually used.

### 11.2 Tracker ownership

With one or more trackers connected:

```text
local immediate state
       |
       v
all enabled compatible trackers
```

Trackers are the remote source of truth for progress/status/score when active, while Tsuzuki maintains an immediate local projection for responsive UI and offline operation.

Library membership, organization, source mappings, and Collections remain Tsuzuki-owned state.

### 11.3 Multiple trackers

If multiple trackers are enabled, Tsuzuki writes compatible updates to all enabled trackers.

There is no mandatory "primary tracker" in the initial architecture.

Divergence handling must be conservative:

- do not silently downgrade progress;
- if local and one tracker agree while another lags, update the lagging tracker when safe;
- if trackers and local state genuinely conflict, require resolution rather than inventing a winner.

### 11.4 Tracker translation

Tracker adapters translate canonical chapter structure to tracker-compatible progress.

For example, extras, specials, or release variants should not advance a regular numeric chapter counter by default unless that tracker explicitly requires different semantics.

### 11.5 Tracker tokens

Tracker credentials/tokens remain device-local in the MVP and must not be casually synchronized through Google Drive.

---

## 12. Collections Engine

### 12.1 Structure

Collections use:

```text
Collection
    |
  Folder
    |
   List
```

The name `List` is used instead of `Source` to avoid collision with Mihon's reading-source concept.

### 12.2 Declarative List model

Conceptually:

```text
List
|- id
|- title
|- provider
|- query
|- sort
|- layout
|- order
|- enabled
└- schemaVersion
```

A List has one primary catalog provider in the MVP.

Cross-provider exhaustive Boolean queries are post-MVP.

### 12.3 Provider-neutral query AST

Core query semantics are provider-neutral.

```text
AND / ALL
OR / ANY
NOT
Predicate(field, operator, value)
```

Potential operators include:

```text
EQUALS
NOT_EQUALS
IN
GREATER_THAN
GREATER_OR_EQUAL
LESS_THAN
LESS_OR_EQUAL
BETWEEN
CONTAINS
```

Potential domains include:

- work type;
- status;
- dates/year;
- chapter/volume count;
- genres/tags/categories;
- demographic;
- country;
- author/artist;
- publisher/magazine;
- provider-specific score fields exposed generically;
- popularity/favorites/rank;
- local library/progress/unread/new-chapter fields;
- availability in installed reading sources.

Relative expressions such as `CURRENT_YEAR` or `TODAY - 30d` may be represented generically.

### 12.4 Query Planner

```text
Tsuzuki Query
    |
    v
Filter Expression
    |
    v
Query Planner
    |
    v
CatalogProvider compiler
```

Each provider declares capabilities.

The planner:

1. pushes supported predicates to the provider;
2. retrieves candidate pages;
3. enriches if required and allowed;
4. applies residual local filters when semantically safe.

Unsupported semantics must not be silently dropped.

### 12.5 Residual pagination

If local residual filtering removes items from a provider page, the planner must continue fetching pages until enough valid results are available or the provider is exhausted.

This preserves correct pagination behavior.

Global sorting by a field that the provider cannot supply or that Tsuzuki cannot exhaustively compute remains unsupported rather than pretending to be globally correct.

### 12.6 Scheduler

A List existing does not imply that it executes.

Use lazy scheduling priorities such as:

```text
VISIBLE
NEXT_SCREEN
BACKGROUND
```

Expected scheduler behavior:

- load visible lists first;
- prefetch near the viewport;
- keep distant lists dormant;
- use bounded provider concurrency;
- cancel stale requests;
- deduplicate identical normalized queries;
- cache by provider/query/sort/page;
- support stale-while-revalidate where appropriate.

Large Collections must remain practical even when they contain hundreds of Lists.

### 12.7 System and user collections

Built-in and user-authored Lists should share one data model.

An `origin` flag may distinguish `SYSTEM` and `USER` definitions.

Users may hide, reorder, or duplicate system content. A duplicated system definition becomes user-owned.

### 12.8 Portable format

Collections must have a versioned portable JSON representation from the beginning.

Example top-level concepts:

```text
schemaVersion
collections
folders
lists
queries
layouts
```

This supports import/export and leaves room for future sharing without requiring a backend.

---

## 13. Google account and private cloud sync

### 13.1 Backend choice

Tsuzuki does not require a custom application backend for private user state.

The initial cloud model is:

```text
Google Sign-In
      +
Google Drive API
      +
Drive appDataFolder
      +
local SQLDelight
      +
Tsuzuki Sync Engine
```

The normal synchronization scope should use the private app-specific Drive area rather than broad user Drive access.

Visible manual exports remain a separate concern.

### 13.2 Local-first behavior

Local SQLDelight state is operational during normal use.

```text
user action
    |
    v
local database
    |
    v
UI immediately
    |
    v
sync outbox
    |
    v
Google Drive
```

Cloud failure must not roll back successful local state.

### 13.3 Logical sync files

Prefer a small set of logical versioned documents rather than thousands of tiny files or one unbounded monolith.

Conceptual layout:

```text
manifest.json
library.json
collections.json
settings.json
source-mappings.json
chapter-overrides.json
fallback-progress.json
```

Exact physical layout may evolve as long as logical ownership remains equivalent.

Synchronizable records should support concepts such as:

```text
stable IDs
schemaVersion
updatedAt
deletedAt / tombstone
revision metadata
```

Tombstones are needed so offline deletions propagate across devices.

### 13.4 What Drive owns

Drive may synchronize private Tsuzuki state such as:

- Library membership and organization;
- Collections, Folders, Lists, and queries;
- settings intended to roam between devices;
- logical source preferences and mappings;
- manual chapter overrides;
- fallback progress only when no tracker is active.

Drive does not synchronize:

- downloaded pages/content;
- image/page caches;
- device-specific transient state;
- raw tracker tokens;
- tracker-owned progress while trackers are active;
- large recomputable source inventories or coverage caches.

### 13.5 Chapter data synchronization

Derived chapter structure may be cached locally.

Drive should synchronize durable user decisions, not every recomputable chapter calculation.

Synchronize:

```text
manual chapter corrections
explicit mappings/overrides
user chapter-resolution preferences
```

Do not require synchronization of:

```text
raw source chapter inventories
coverage calculations
temporary confidence scores
provider caches
```

### 13.6 Source mappings across devices

Synchronize the logical mapping and preference, but do not assume an extension is installed on every device.

Example:

```text
Drive says preferredSource = MangaDex

Device A:
MangaDex installed -> reconnect mapping

Device B:
MangaDex unavailable -> degraded mapping
                         + offer setup/alternative
```

### 13.7 Conflict resolution

Independent changes should merge automatically.

Example:

```text
Phone: rename Collection
Tablet: add List
=> merge
```

Conflicting edits to the same property must not use blind last-write-wins.

Example:

```text
Phone: Collection name = Favorites
Tablet: Collection name = Manga
=> explicit conflict resolution
```

### 13.8 Progress fallback

Remote progress policy is:

```text
1. local immediate state
2. connected tracker(s), when present
3. Google Drive fallback only when no tracker is active
```

When trackers are active, Drive must not independently compete as another remote progress authority.

If the user previously had Drive fallback progress and later connects a tracker with conflicting values, Tsuzuki must resolve the transition rather than silently overwriting one side.

### 13.9 Sync is not backup

Google Drive synchronization and backup remain separate features.

```text
Sync
-> continuous private state across devices

Backup
-> explicit point-in-time restoration artifact
```

A user must be able to create/restore a backup without signing into Google.

---

## 14. Mihon migration and compatibility

### 14.1 Existing Mihon libraries

The first Tsuzuki migration is automatic, conservative, and lossless.

Conceptually:

```text
Existing Mihon library manga
          |
          v
CanonicalTitle UUID
          |
          +-> SourceTitleMapping
          |
          +-> LibraryEntry
```

The original Mihon operational records remain available.

Canonical enrichment may then run in the background.

If the title cannot be identified in any catalog, it remains readable as an unresolved/source-only canonical title.

### 14.2 Preservation requirements

Migration must preserve access to existing:

- library entries;
- categories;
- source mappings;
- chapter data;
- history/progress;
- downloads;
- local titles;
- other relevant Mihon state.

Canonical resolution must never be a prerequisite for successful migration.

### 14.3 Adapter boundary

During migration and long-term operation:

```text
Tsuzuki canonical state
        <->
Mihon operational state
```

Only the data needed by the Reader, downloader, updater, source layer, and related Mihon components should be projected back into Mihon abstractions.

---

## 15. Home

Home is a lazy composition of independent sections, not a monolithic remote feed.

Potential sections include:

```text
Continue Reading
New Chapters
Trending
Top Rated
New / Recently Released
Your Collections
Recommended / Related
```

Local sections should appear immediately.

Remote sections load independently and may use cache/stale state.

A remote provider failure must not prevent local Home sections from working.

---

## 16. Title detail page

The detail page represents the canonical work rather than a single source.

Conceptual content:

```text
cover
title / aliases
authors
status
genres / tags
provider-specific scores

[Read / Continue]
[+ Library]

synopsis
reading progress
chapters
reading source
coverage
related works
recommendations
```

Changing the reading source must not replace title metadata, Library identity, or tracking identity.

---

## 17. Downloads

Mihon's downloader remains the implementation mechanism.

Tsuzuki may present downloads grouped by canonical title and chapter even if the underlying content originated from different source mappings.

The MVP does not rewrite the downloader.

Downloads and pages are local device data and are not synchronized to Google Drive.

---

## 18. Offline behavior and graceful degradation

Tsuzuki is local-first.

Offline, the application should continue to support as much as local state permits:

```text
downloaded chapters
local manga
Library
cached metadata
cached Collection results
Reader
local history/progress
```

Remote discovery, fresh provider queries, tracker writes, and cloud sync naturally pause.

When connectivity returns, queued/outstanding synchronization may resume without blocking core UI.

General failure principle:

> **Prefer degraded functionality over blocking functionality.**

Examples:

```text
Catalog provider unavailable
-> lose fresh provider-backed discovery, not Library/Reader

Preferred reading source unavailable
-> try permitted alternatives or ask user

Google unavailable
-> remain local and queue sync

Tracker unavailable
-> preserve local progress and retry safely

Identity resolver uncertain
-> do not auto-merge

Source resolver uncertain
-> ask user

Chapter resolver uncertain
-> expose uncertainty; do not fabricate structure
```

---

## 19. Storage strategy

Tsuzuki should use Mihon's existing SQLDelight-based persistence patterns for durable local data where practical.

Organizational structures should use normal relational tables, while evolving declarative models such as query ASTs, layouts, and some sync payloads may use versioned JSON fields when that reduces migration churn without sacrificing integrity.

Potential logical tables include:

```text
tsuzuki_titles
tsuzuki_external_ids
tsuzuki_metadata_provenance
tsuzuki_library_entries
tsuzuki_source_mappings
tsuzuki_canonical_chapters
tsuzuki_chapter_variants
tsuzuki_chapter_overrides
tsuzuki_collections
tsuzuki_folders
tsuzuki_lists
tsuzuki_catalog_cache
tsuzuki_sync_outbox
```

Exact schema is an implementation-plan concern, but the ownership boundaries in this specification are fixed.

---

## 20. Security and privacy

Tsuzuki should minimize remote authority and credential exposure.

Requirements:

- Google account is optional.
- Trackers are optional.
- Reading remains possible without Google or trackers.
- Broad Google Drive access is not required for normal sync.
- Tracker tokens must remain device-local in the MVP.
- Logs must not contain Google tokens, tracker tokens, cookies, authorization headers, or other sensitive credentials.
- Tsuzuki does not require a central database of users' manga libraries.
- Remote analytics/telemetry is not a requirement of the MVP architecture.

A future public/social backend may be added for public/shared features while private Library state remains user-owned.

---

## 21. Diagnostics and observability

Provide local diagnostic logging for major Tsuzuki subsystems, including:

```text
CatalogProvider
IdentityResolver
SourceResolver
ChapterResolver
QueryPlanner
QueryScheduler
SyncEngine
Tracker adapters
Mihon adapters
```

Diagnostics should be exportable for troubleshooting without exposing credentials.

---

## 22. Upstream Mihon strategy

Tsuzuki is upstream-friendly by design.

Conceptual ownership categories:

```text
UNCHANGED UPSTREAM
ADAPTED
TSUZUKI-OWNED
```

Prefer:

```text
Tsuzuki module
     |
   adapter
     |
Mihon API/domain
```

over spreading Tsuzuki-specific behavior across many Mihon components.

Deep Mihon changes are allowed when technically justified, but divergence must be deliberate.

Primary Mihon infrastructure to preserve rather than rewrite includes:

```text
Reader
Downloader
Extension system
Source execution
Chapter/page fetching
Networking
WebView/source authentication
Background jobs
Local manga infrastructure
Image handling
Useful backup/tracking infrastructure
```

---

## 23. MVP milestone definition

The first major Tsuzuki milestone is split into three vertical sub-milestones.

### MVP-V1: canonical reading vertical

V1 proves the end-to-end product model:

```text
Discover/Search
      |
      v
CatalogItem
      |
      v
CanonicalTitle
      |
      v
Library
      |
      v
Read
      |
      v
Source Resolver
      |
      v
Canonical Chapters
      |
      v
Mihon Reader
      |
      v
Canonical Progress
```

V1 includes:

- minimal canonical domain;
- Kitsu-backed initial catalog integration;
- unified initial Search;
- basic Discover;
- canonical Library;
- conservative Mihon-library migration;
- source preferences by language;
- title-level source overrides;
- Source Resolver;
- persisted source mappings;
- initial Canonical Chapter Engine;
- `ChapterVariant`;
- basic gap detection;
- basic coverage;
- per-chapter fallback;
- reader/progress integration;
- basic Home sufficient to prove the architecture.

### MVP-V2: Collections

V2 adds:

- provider-neutral query AST;
- Query Planner;
- capability-aware provider compilation;
- residual filtering and correct pagination;
- lazy Query Scheduler;
- caching/request deduplication;
- Collection -> Folder -> List;
- system/user definitions;
- import/export using a versioned JSON format;
- user-facing Collection management.

### MVP-V3: private sync

V3 adds:

- Google authentication;
- Drive `appDataFolder` synchronization;
- sync outbox;
- merge/conflict behavior;
- Library/Collection/settings/source-mapping sync;
- chapter-override sync;
- tracker-aware Drive progress fallback;
- schema-version migration for cloud documents.

MVP-V1 + MVP-V2 + MVP-V3 together constitute the first complete Tsuzuki product milestone.

---

## 24. Post-MVP directions

Post-MVP work may include:

- advanced cross-provider queries;
- richer identity resolution;
- MangaBaka enrichment/identity assistance;
- more complete edition modeling;
- richer local recommendations;
- improved extension/source management;
- shared Collections;
- public profiles or social features;
- optional public/social backend;
- mature Home personalization;
- deep visual redesign.

These are not prerequisites for validating the core architecture.

---

## 25. Explicit non-goals for the first milestone

The first Tsuzuki milestone does **not** require:

- a custom Tsuzuki backend;
- a central hosted manga catalog;
- a social network;
- Tsuzuki-native comments/reviews;
- AI recommendation infrastructure;
- embedding/vector infrastructure;
- exhaustive indexing of every provider;
- arbitrary exhaustive multi-provider Boolean queries;
- synchronized downloaded pages/content;
- synchronized tracker credentials;
- a new Reader engine;
- a new downloader engine;
- a new extension execution engine;
- hosting manga content;
- a complete visual redesign before the architecture works.

---

## 26. Test strategy

Resolver correctness is more important than merely avoiding crashes.

Recommended test pyramid:

```text
Domain unit tests
        ^
Provider contract tests
        ^
Resolver fixture tests
        ^
Database/migration tests
        ^
Sync merge tests
        ^
Mihon adapter integration tests
        ^
Critical UI/E2E flows
```

Important fixtures should intentionally include difficult cases such as:

```text
Chapter 10
Chapter 10.5
Extra 10
Chapter 11
```

and:

```text
Source A -> 98 chapters
Source B -> 100 chapters
Metadata -> 100 chapters
```

and distinct works with deceptively similar titles that must not merge.

Architectural rules should be backed by tests where practical.

Examples:

```text
A low-confidence identity match cannot auto-merge.

A catalog-provider outage cannot remove local Library access.

A title without catalog metadata remains readable.

A Source mapping cannot become canonical identity.

An EXTRA chapter does not advance normal tracker progress by default.

A fallback ChapterVariant marks the CanonicalChapter as read.

A failed cloud sync cannot roll back successful local progress.

A Mihon migration cannot require catalog resolution.
```

---

## 27. Schema and format evolution

All Tsuzuki-owned persisted formats must be versioned and migratable.

This includes:

- local SQLDelight schema;
- Collection JSON;
- Drive synchronization documents;
- portable backup/export formats.

Conceptually:

```text
v1
 |
 migrate
 |
v2
 |
 migrate
 |
v3
```

Users must not need to reinstall or discard state because a Tsuzuki schema evolves.

---

## 28. Technical roadmap

Implementation should proceed by dependency rather than by visual feature order.

```text
0. Fork foundation
   |
1. Tsuzuki canonical domain
   |
2. CatalogProvider abstraction + Kitsu
   |
3. Unified Search / basic Discover
   |
4. Canonical Library + Mihon migration
   |
5. Source Resolver
   |
6. Canonical Chapter Engine
   |
7. Reader/progress integration
   |
------ MVP-V1 ------

8. Collections Query Engine
   |
9. Collection UI + import/export
   |
------ MVP-V2 ------

10. Google authentication
    |
11. Drive Sync Engine
    |
12. Tracker/Drive reconciliation
    |
------ MVP-V3 ------

13. Home maturation
    |
14. Source-management improvements
    |
15. Recommendations
    |
16. Deep visual redesign
```

The roadmap may be split into smaller implementation plans, but implementation must not reorder dependencies in a way that weakens the architectural invariants.

---

## 29. Success criteria for the first major milestone

The first major milestone is successful when all of the following are true:

1. A user can discover or search for a work without thinking in terms of reading sources.
2. A remote catalog result can become a Tsuzuki-owned canonical title without using the provider ID as canonical identity.
3. A title can exist and remain usable without catalog metadata.
4. Existing Mihon Library content migrates without loss and remains readable.
5. A title can enter Library without having a reading source resolved.
6. Reading can resolve a preferred source on demand and ask the user when confidence is insufficient.
7. Multiple reading sources can map to one canonical title.
8. Tsuzuki can detect at least basic chapter gaps and distinguish regular chapters from common extras/special cases.
9. Multiple releases can map to one canonical chapter through `ChapterVariant`.
10. Progress follows the canonical chapter rather than a specific source chapter.
11. A missing chapter can be read from an alternative source without migrating the entire work.
12. Catalog-provider outages do not disable local Library or Reader functionality.
13. Collections can express portable provider-neutral queries and execute lazily.
14. Google Drive sync is optional, local-first, and can merge independent changes.
15. With trackers connected, tracker progress remains the remote authority; Drive progress acts only as fallback when no tracker is active.
16. Backup remains available independently of Google Drive sync.
17. Core Mihon Reader/downloader/extension infrastructure remains reusable and upstream updates remain practical.

---

## 30. Final design summary

The complete system is conceptually:

```text
                         TSUZUKI
                            |
       +--------------------+--------------------+
       |                    |                    |
    Catalog             User Domain          Reading
       |                    |                    |
CatalogProvider      CanonicalTitle       SourceResolver
Collections          LibraryEntry             |
Search               Collections          Mihon Sources
Discover             Preferences               |
       |                    |             ChapterResolver
       +----------+---------+                    |
                  |                       CanonicalChapter
                 Sync                            |
                  |                         ChapterVariant
            Google Drive                         |
                  |                          Mihon Reader
               Tracking
                  |
          MAL / Kitsu / etc.
```

Below that, Mihon remains the operational foundation:

```text
MIHON INFRASTRUCTURE

Reader
Downloader
Extensions
Source execution
Networking
WebView/Auth
Background jobs
Local manga
Chapter/page fetching
```

The long-term product direction can be summarized by two rules:

> **Mihon remains the mechanism. Tsuzuki becomes the experience.**

> **Tsuzuki owns identity; providers and Sources provide evidence and content.**
