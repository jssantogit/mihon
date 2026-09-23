# Tsuzuki — Modular Manga Runtime Architecture

**Status:** Draft for review  
**Date:** 2026-09-20  
**Baseline:** tsuzuki/bootstrap @ 4e339973b68bd3cd4801a2d4269511d8a4b15f6d  
**Primary product reference:** Nuvio / Stremio-style modular experience, adapted deliberately to manga rather than copied literally  
**Primary audience:** Tsuzuki developers implementing the post-MVP product architecture

---

## 1. Executive decision

Tsuzuki will evolve from a Mihon-derived application with a canonical layer on top of sources into a **modular manga runtime**.

The product principle is:

> **Tsuzuki owns the experience and the canonical user state. Integrations provide editorial/catalog/metadata capabilities. Add-ons provide installable/extensible capabilities, initially centered on chapter content. The Core reconciles both without making any external provider the identity of a work.**

Tsuzuki Core owns:

- canonical work identity;
- canonical chapter identity;
- Library membership and status;
- reading progress and Continue Reading;
- chapter update state;
- collections, folders, lists, and Home composition;
- Reader;
- canonical downloads/offline ownership;
- content resolution policy;
- Integration and Add-on capability routing;
- local persistence;
- local-first synchronization;
- product UI and navigation.

Tsuzuki Core must **not** depend semantically on Kitsu, MyAnimeList, MangaDex, MangaFire, Google Drive, or any other specific external service.

The chosen architecture is **capability-based Core with separate Integration and Add-on ecosystems**.

- **Integrations** are first-party adapters shipped and supported by Tsuzuki. Kitsu and MyAnimeList are initial examples.
- **Add-ons** are installable/extensible providers managed separately from Integrations. Mihon extensions are the first runtime. Remote manifest add-ons and torrent-capable content are future runtimes/capabilities.
- Integrations and Add-ons may satisfy compatible capability contracts, but they do not share lifecycle, trust, installation, or security semantics.

The new application starts **empty by default**. No catalog, metadata provider, Home feed, or content source is silently enabled.

Cloud sync will move from Google Drive to **Supabase**. Tsuzuki will support **Supabase-native login/registration only**. Google login and Google Drive are not part of the target product.

---

## 2. Why this redesign exists

The post-MVP canonical work established an important intermediate architecture:

- CanonicalTitle gives a work a Tsuzuki-owned identity.
- CanonicalChapter gives chapters a Tsuzuki-owned logical identity.
- ChapterVariant separates a logical chapter from a provider-specific release.
- canonical Library and reading progress prevent source IDs from being the only user-state authority.
- Collections already model user-controlled lists and provider-backed queries.
- Mihon Reader, downloader, extension stores, extension installation, source loading, and local content remain mature implementation assets.

However, runtime authority still flows in the wrong direction in important paths.

Current chapter materialization is effectively:

~~~text
Source representation
      ↓
source chapter inventory
      ↓
parse/reconcile
      ↓
CanonicalChapter
~~~

This means a provider with one visible chapter can accidentally make the Tsuzuki work appear to have one chapter. It also keeps the product conceptually tied to “reading sources”, per-title source configuration, source migration, and source-owned catalogs.

The desired direction is:

~~~text
Integrations + chapter evidence
              ↓
      CanonicalChapter graph
              ↑
  provisional Add-on evidence

CanonicalChapter
      ↓
Content Resolver
      ↓
Add-on content options
      ↓
Reader
~~~

The previous canonical work is therefore **not discarded**. It becomes the foundation of the new architecture.

---

## 3. Product north star

The intended experience is “Nuvio for manga” in product philosophy:

- one canonical title instead of one title per source;
- discovery independent of content delivery;
- content providers selected when content is needed;
- sources/add-ons hidden from the primary browsing experience;
- modular Integrations and Add-ons configured in Settings;
- user-composable Home;
- Continue Reading as the primary reading-history surface;
- no migration concept;
- no source catalog navigation as a primary product surface;
- a clean four-destination shell.

The analogy is:

~~~text
Nuvio / video                         Tsuzuki / manga

movie identity                       CanonicalTitle
episode identity                     CanonicalChapter
metadata integration                 Kitsu / MAL Integration
stream provider                      content Add-on
stream option                        ContentOption
player                               Reader
continue watching                    Continue Reading
catalog collections                  Collections / Home sections
P2P delivery                         torrent-backed chapter delivery
~~~

This analogy guides product behavior, not a byte-for-byte protocol copy.

---

## 4. Core architectural invariants

These are hard constraints for implementation.

### 4.1 Canonical identity is Tsuzuki-owned

A work does not belong to Kitsu, MAL, MangaDex, MangaFire, or any Add-on.

External identities are evidence/mappings attached to a CanonicalTitle.

Display-title equality alone must never automatically merge works.

A disabled or removed Integration must not invalidate an existing Library title.

### 4.2 Canonical chapter identity is provider-independent

A CanonicalChapter is a Tsuzuki logical chapter.

Provider-specific releases, languages, scanlation groups, URLs, archives, and torrents are content options/evidence associated with that logical chapter.

Removing an Add-on must not delete the chapter concept.

### 4.3 Chapter count is catalog extent, not proof of the numbering structure

A provider reporting `chapterCount = 205` provides a useful catalog outline, even when no reading Add-on has been bound yet. Tsuzuki displays **count-derived numbered slots** in the same chapter list as chapters supported by actual evidence. This prevents a work with a known count from appearing to have no chapters merely because no Add-on currently supplies pages.

Count-only slots are **provisional presentation**, not 205 confirmed `CanonicalChapter` records and not chapter/release evidence. Their default labels (`1…205`) are tentative; authoritative or reliably parsed evidence can later supply the actual numbering and extra/special/half/zero chapters. Count-only slots have no fabricated source URL, chapter key, pages, content availability, download state or read progress.

When a user explicitly opens or downloads a count-only slot, Tsuzuki may materialize **that single selected slot** as a low-confidence, provenance-distinguishable provisional `CanonicalChapter` with a stable Tsuzuki-owned ID. This allows the normal content selector and canonical read/download state to work without bulk-creating unverified chapter identities. Materialization must be serialized with source-evidence reconciliation, reusing an existing matching canonical identity, never silently overwriting an existing progress key or mapping a mismatched source chapter by number alone. Reliable external evidence can upgrade the slot while preserving the canonical ID.

One list contains the union of reported count-derived slots and observed canonical chapters, ordered by structured identity. Reported totals from different Integrations remain separate metadata; a deterministic display policy (currently the greatest valid count) must not turn disagreement into verified structure. No ordinary "provisional" badges or count-only explanatory block should replace usable chapter rows.

### 4.4 Local state remains authoritative for immediate behavior

User actions write locally first.

Network or sync failure must not roll back a successful local Library, progress, collection, or preference action.

Cloud sync converges state later.

### 4.5 Integrations and Add-ons are different trust/lifecycle systems

The UI may present both as modular capabilities, but internally:

- an Integration is shipped with Tsuzuki and explicitly enabled/configured;
- an Add-on is installable/removable and may originate from a third-party repository or remote manifest;
- executable Mihon extensions have APK/signature/trust semantics;
- remote manifest Add-ons must not imply arbitrary remote code execution.

### 4.6 Cloud sync cannot install executable code

Synced configuration must never silently trust or install an APK-based Add-on on another device.

Trust decisions and executable installation remain local security actions.

### 4.7 No hidden provider choice

If a preferred Add-on does not have a requested chapter and automatic fallback is disabled, Tsuzuki opens the content selector.

Automatic fallback exists only when globally enabled by the user.

---

## 5. Target system context

~~~mermaid
flowchart TD
    UI["Tsuzuki UI\nHome · Search · Library · Settings"]
    CORE["Tsuzuki Core\nCanonical identity · chapters · progress · collections · resolver"]
    IR["Integration Registry"]
    AR["Add-on Registry"]
    SYNC["Local-first Sync"]
    DB["SQLDelight"]
    SUPA["Supabase\nAuth + Postgres/RPC"]
    KITSU["Kitsu Integration"]
    MAL["MAL Integration"]
    MIHON["Mihon Extension Runtime"]
    REMOTE["Remote Manifest Runtime"]
    LOCAL["Local Content Add-on"]
    READER["Reader / Downloads"]

    UI --> CORE
    CORE --> IR
    CORE --> AR
    CORE --> DB
    CORE --> READER
    CORE --> SYNC
    SYNC --> SUPA
    IR --> KITSU
    IR --> MAL
    AR --> MIHON
    AR --> REMOTE
    AR --> LOCAL
~~~

Dependencies point inward toward Core contracts.

Vendor/network/runtime-specific code stays in adapters.

---

## 6. Product navigation

The primary navigation becomes:

1. **Home**
2. **Search**
3. **Library**
4. **Settings**

The following current primary tabs disappear:

- Updates
- History
- Browse
- More as a separate catch-all destination

Settings replaces the product role currently held by More/Browse configuration surfaces.

The following product concepts also disappear from normal UX:

- source-specific browsing;
- Popular/Latest for a reading source;
- migration between sources;
- per-title Reading Source settings;
- per-title source ordering;
- SOURCE_ONLY identity labels;
- source IDs in Library cards;
- per-title language configuration.

Legacy screens may survive temporarily as internal compatibility code during rollout, but they are not part of the target navigation or product model.

---

## 7. Fresh-install behavior

A new Tsuzuki installation is intentionally unconfigured.

### Home

Home contains no hero and no catalog/discovery rows by default.

If the user has not started reading anything, Home may be visually empty except for setup affordances.

Continue Reading is a system section that appears automatically only after there is active reading progress.

### Search

Without an enabled Integration that supplies search/discovery, Search does not silently fall back to Kitsu or a source.

It shows a clear empty state directing the user to configure an Integration.

### Library

Library starts empty.

### Settings

Settings exposes:

- Account;
- Integrations;
- Add-ons;
- Home / Collections;
- Reading;
- Downloads;
- Sync;
- Appearance and other normal application settings.

No Integration or Add-on is enabled silently on first launch.

---

## 8. Account and cloud identity

### 8.1 Supported account model

Target cloud identity uses **Supabase Auth**.

Initial supported account flow:

- e-mail/password registration;
- e-mail/password login;
- password reset/recovery as required by the chosen Supabase Auth configuration.

There is no Google login and no Google OAuth dependency in the target product.

Social login providers are out of scope unless explicitly designed later.

### 8.2 Local use

The architecture remains local-first.

The recommended product behavior is that authentication is required for cloud sync, not for the basic offline/local Reader and Library runtime.

If product policy later makes account login mandatory, the persistence/sync architecture does not change.

### 8.3 Secret handling

Supabase service-role credentials must never ship in the client.

Only publishable/anon client configuration is allowed in the app.

Integration access/refresh tokens are **device-local by default** in the first implementation. Non-secret Integration settings may sync; credentials require a separate explicit security design before cross-device synchronization.

---

## 9. Integrations

Integrations are first-party adapters bundled with Tsuzuki.

They are not installed from Add-on repositories.

Examples:

- Kitsu
- MyAnimeList
- future AniList / MangaUpdates / other supported services

An Integration is explicitly enabled and configured by the user.

### 9.1 Integration capabilities

The Core should depend on narrow capability contracts rather than concrete Integration classes.

Initial target contracts:

~~~text
SearchProvider
DiscoveryProvider
MetadataProvider
ChapterEvidenceProvider
RatingsProvider
TrackingProvider
~~~

A specific Integration can implement any subset.

Example target shape:

~~~text
KitsuIntegration
  SearchProvider
  DiscoveryProvider
  MetadataProvider
  ChapterSummary/ChapterEvidence where reliable

MalIntegration
  SearchProvider
  MetadataProvider
  RatingsProvider
  TrackingProvider
  ChapterSummary/ChapterEvidence where reliable
~~~

The exact API capability set must reflect what the upstream service actually provides. A declared chapter count alone is not ChapterEvidenceProvider output.

### 9.2 Dynamic availability

Some capabilities may require extra configuration or authentication.

The registry exposes only capabilities currently usable for that Integration.

For example, tracking may require an authenticated MAL account while some metadata capability may have different requirements.

### 9.3 Metadata reconciliation

Metadata is not allowed to become nondeterministic when multiple Integrations are enabled.

Each metadata observation retains provenance.

Scalar-field resolution uses a stable configured Integration priority and feature toggles.

Ratings should retain provider identity rather than averaging unrelated rating systems into one synthetic score.

Future UI may expose provider-specific scores such as MAL score.

---

## 10. Add-ons

Add-ons are installable/extensible providers.

The initial UI location is:

~~~text
Settings
└── Add-ons
    ├── Installed
    ├── Repositories
    └── Updates
~~~

The interface label is **Add-ons**.

The user does not interact with Mihon’s internal distinction between extension package and individual Source.

### 10.1 Mihon Extension runtime

The existing Mihon extension ecosystem is the first Add-on runtime.

Existing infrastructure to reuse includes:

- ExtensionStoreRepository;
- ExtensionApi;
- ExtensionManager;
- ExtensionLoader;
- SourceManager;
- signature/trust handling;
- install/update/uninstall;
- multi-source extension support.

A single installed extension appears as one Add-on in Tsuzuki even when it exposes multiple internal Source objects or languages.

Internal Source search remains valid as an implementation mechanism for locating a provider-specific work.

It is not exposed as a catalog product surface.

### 10.2 Add-on capabilities

Initial capabilities:

~~~text
ContentProvider
ChapterProbeProvider
~~~

The architecture must allow later Add-ons to provide additional capabilities such as:

~~~text
Search/Discovery contributions
Metadata
Recommendations
other future Tsuzuki-defined capabilities
~~~

This future extensibility must not collapse Add-ons and Integrations into one lifecycle abstraction.

### 10.3 Remote manifest Add-ons

A future remote Add-on runtime should be manifest-driven and inspired by Stremio’s separation between declarative manifest/resources and the client.

Desired properties:

- manifest declares identity, version, configuration and capabilities;
- requests use an explicit remote protocol;
- external responses are parsed/validated at the boundary;
- remote Add-ons do not execute arbitrary downloaded application code merely because they are remote;
- protocol versioning is explicit;
- capability additions are additive.

Binary compatibility with existing Stremio Add-ons is **not** a requirement of the base Tsuzuki protocol.

A Stremio compatibility adapter may be introduced later where semantics are actually compatible.

---

## 11. Canonical title identity

The current CanonicalTitle and external-identity direction is retained.

Conceptually:

~~~text
CanonicalTitle
├── Tsuzuki ID
├── display metadata snapshot
└── ExternalIdentity[]
    ├── kitsu:<id>
    ├── mal:<id>
    └── future:<id>
~~~

### 11.1 Search aggregation

Search can query multiple enabled SearchProvider capabilities concurrently with bounded fan-out.

Results are normalized into provider candidates.

Deduplication rules:

1. known external identity already attached to an existing CanonicalTitle is authoritative;
2. verified cross-provider identity evidence may merge candidates;
3. title similarity is scoring evidence only;
4. title equality alone never auto-merges;
5. ambiguous candidates remain distinct until stronger evidence or explicit reconciliation exists.

This preserves the anti-regression rule learned from source migration work: human-readable title strings are not canonical identity.

### 11.2 Materialization

Search results should not force all remote results into durable CanonicalTitle rows immediately.

A canonical title is materialized when needed by an actual user/domain action such as opening a durable detail context, adding to Library, starting reading, or another explicit materialization boundary chosen during implementation.

---

## 12. Canonical chapter model

The current CanonicalChapter concept remains, but chapter creation becomes evidence-driven instead of source-inventory-driven.

### 12.1 Chapter evidence

Introduce a first-class concept equivalent to:

~~~text
ChapterEvidence
- id
- canonicalTitleId
- producerKind          INTEGRATION | ADDON
- producerId
- externalChapterKey?
- rawLabel
- rawNumber?
- volume?
- title?
- chapterType?
- observedAt
- confidence
- authorityClass
- rawMetadata
~~~

Exact persistence naming may vary, but provenance must not be lost.

### 12.2 Effective chapter state

A CanonicalChapter derives a visible confirmation state:

- **CONFIRMED** — sufficient trusted editorial evidence exists;
- **PROVISIONAL** — chapter is currently supported only by provisional Add-on evidence or incomplete evidence;
- **CONFLICTED** — evidence cannot safely be reconciled into one chapter identity.

The user requested that provisional chapters have a small visual indicator rather than being completely indistinguishable.

### 12.3 Reconciliation

The reconciler must:

- preserve existing stable external chapter mappings;
- attach exact/high-confidence equivalent evidence to an existing CanonicalChapter;
- auto-reconcile only when confidence is high;
- keep ambiguous observations separate rather than guessing;
- promote provisional chapters when an Integration later confirms them;
- never delete a CanonicalChapter merely because a provider stops returning it;
- preserve progress/history/download references through promotion/reconciliation.

### 12.4 Counts

Reported chapter counts are metadata only.

They may be displayed where useful but do not produce chapter identities.

If an Integration knows a count but not a chapter list, the detail screen shows only actual known CanonicalChapters.

---

## 13. Content bindings and provider identity

Current SourceRepresentation is useful transitional data but is too Mihon-specific as a permanent abstraction.

The target concept is a provider-neutral **ContentBinding**.

Conceptually:

~~~text
ContentBinding
- id
- canonicalTitleId
- addonId
- providerTitleKey
- matchConfidence
- verifiedByUser
- availability
- runtimePayload
- createdAt
- updatedAt
~~~

For a Mihon Extension Add-on, runtimePayload may resolve to:

- internal sourceId;
- mihonMangaId;
- source URL;
- other Mihon operational identity.

For a remote Add-on, it may contain a remote title identifier.

For local content, it may contain a local collection/root identity.

Runtime-specific fields must not become required fields of the Core domain.

---

## 14. Chapter content resolution

The canonical user flow is:

~~~mermaid
sequenceDiagram
    participant U as User
    participant D as Manga Detail
    participant C as ContentResolver
    participant P as Preferred Add-on
    participant A as Other Add-ons
    participant S as Selector
    participant R as Reader

    U->>D: Tap canonical chapter
    D->>C: Resolve chapter
    C->>P: Query preferred provider if set
    C->>A: Query relevant providers as needed
    alt no preferred provider
        C-->>S: Show ranked ContentOptions
        U->>S: Choose option
        S->>C: Save title preference
        C-->>R: Prepare selected content
    else preferred provider has content
        C-->>R: Open directly
    else preferred unavailable and auto fallback off
        C-->>S: Show alternatives
    else preferred unavailable and auto fallback on
        C-->>R: Open best ranked fallback
    end
~~~

### 14.1 First choice

When a title has no remembered provider preference, tapping a chapter opens the content selector.

The selected Add-on becomes the preferred content provider for that title.

Preference scope is **per CanonicalTitle**, never global.

Examples:

~~~text
Dandadan → MangaDex
Berserk  → MangaFire
Monster  → MangaBall
~~~

### 14.2 Missing preferred provider

If the preferred Add-on cannot provide the requested chapter:

- automatic fallback disabled: open selector;
- automatic fallback enabled: choose the highest-ranked valid fallback.

Automatic fallback is a **global Settings option** and is off by default.

### 14.3 Changing preference after fallback

When fallback is manual and the user selects a different Add-on because the preferred one is unavailable, Tsuzuki asks whether the newly selected Add-on should become the preferred provider for that title.

It must not silently change the title preference.

### 14.4 Voluntary source change

The Reader exposes a **Trocar fonte / Change source** action that reopens the content selector for the current CanonicalChapter.

### 14.5 Selector contents

Every option should show when available:

- Add-on name;
- language;
- scanlation group;
- release/upload date.

All valid options remain visible.

Tsuzuki may order options, but initial UX does not hide alternatives behind a single “best” result.

### 14.6 Language

There is no per-title language configuration.

Settings may contain globally preferred language(s).

Preferred languages affect ordering, not visibility. Other languages remain selectable.

### 14.7 Ranking

Initial ranking order should be deterministic and may consider:

1. title preferred Add-on;
2. preferred language(s);
3. content readiness/local availability;
4. stable Add-on/content heuristics;
5. release metadata as a later tie-breaker.

The first UI does not require a user-managed global Add-on priority list.

---

## 15. Resolution performance and caching

Resolution is on-demand.

Tsuzuki must not scan all installed Add-ons for every chapter when avoidable.

Preferred behavior:

1. use persisted ContentBindings first;
2. query the preferred Add-on first;
3. reuse cached chapter availability/options with a bounded TTL;
4. fan out to other relevant Add-ons when the selector is needed or when background probing is explicitly running;
5. coalesce identical in-flight lookups;
6. cancel stale resolution work when navigation changes.

The first time an Add-on sees a work, it may internally search for the title to establish a ContentBinding.

Subsequent chapter resolution should use the binding rather than repeat broad title search.

---

## 16. Content delivery and Reader boundary

The Reader must stop requiring every future content mechanism to pretend to be a Mihon Source.

Target concept:

~~~text
PreparedChapterContent
├── MihonOperationalChapter
├── LocalArchive
├── LocalDirectory
├── CanonicalDownloadedArtifact
└── future prepared transport
~~~

The existing MihonCanonicalReaderGateway remains the compatibility path for Mihon Extension Add-ons.

A new provider-neutral preparation boundary is introduced beside/above it.

The Reader itself already has useful loaders for:

- HTTP pages;
- downloaded content;
- local directories;
- archives;
- EPUB.

These should be reused rather than replaced.

---

## 17. Torrent support

Torrent is a **content delivery mechanism**, not a catalog identity and not necessarily an Add-on type.

A torrent-capable Add-on may return a ContentOption whose delivery descriptor contains:

~~~text
TorrentDelivery
- magnet URI and/or info hash
- trackers where applicable
- explicit file index/path where applicable
- expected artifact type
- optional size/display metadata
~~~

### 17.1 First torrent milestone

Initial manga torrent support should prioritize correctness over progressive streaming sophistication.

Preferred initial path:

~~~text
ContentOption
    ↓
torrent engine acquires the requested artifact/file
    ↓
CBZ/ZIP/EPUB/directory prepared locally
    ↓
existing Reader loader
~~~

Progressive piece-level image streaming is not required for the first implementation.

### 17.2 Multi-file torrents

The Add-on should provide explicit chapter-to-file mapping where possible.

Core must not silently guess a chapter file from filenames when an explicit file index/path is available.

Heuristic matching is fallback behavior and must be testable/observable.

### 17.3 Cloud scope

Torrent payloads, page caches, and downloaded chapter files are never synchronized to Supabase.

---

## 18. Local content

Local content participates in the same user-facing resolver model as an Add-on.

Local content may receive a natural ranking advantage when the requested CanonicalChapter is already present on-device.

The user should not need a separate source-centric reading model for local files.

The existing LocalSource and archive/directory/EPUB loaders are implementation assets behind the new Local Content adapter.

---

## 19. Canonical downloads

A downloaded chapter belongs to a **CanonicalChapter**, not to the continued installation of the Add-on that originally supplied it.

Target model:

~~~text
CanonicalDownloadArtifact
- canonicalChapterId
- local artifact locator
- format
- completedAt
- originatingAddonId?
- originatingContentOptionId?
- integrity metadata
~~~

Origin metadata is diagnostic/provenance information.

It must not be required to reopen the downloaded chapter.

### Download behavior

When the user downloads a chapter:

- use the title’s preferred Add-on when it can provide the chapter;
- otherwise open the content selector;
- after download completes, offline reading resolves to the canonical local artifact first.

Uninstalling or disabling the originating Add-on does not invalidate the download.

The current Mihon downloader may remain an initial backend through an adapter, but new architecture must not permanently encode sourceId/mangaId as canonical download identity.

---

## 20. Updates and new-chapter state

There is no Updates tab.

Updates become state attached to a work.

### 20.1 Primary update discovery

Enabled Integrations that can provide chapter/release evidence are the primary editorial update channel.

### 20.2 Add-on supplemental probes

For Library titles with known ContentBindings, Tsuzuki may perform lightweight Add-on probes in the background.

These probes are allowed to discover a chapter ahead of an Integration.

Such a chapter becomes PROVISIONAL.

### 20.3 Badge semantics

A provisional chapter is allowed to generate a new-chapter badge.

The badge is not “total unread chapters”.

It represents newly observed chapter state relative to the user’s acknowledged/read progression.

If three newly observed chapters exist:

~~~text
+3 → user reads one → +2 → +1 → gone
~~~

This requires explicit new-chapter observation/acknowledgment state rather than deriving the badge from all unread chapters.

---

## 21. Continue Reading and history

There is no dedicated History tab.

Continue Reading is the primary user-facing reading continuity surface.

### 21.1 Entry rule

Adding a title to Library never places it in Continue Reading by itself.

Only actual reading progress makes it eligible.

### 21.2 Remove from Continue Reading

“Remove from Continue Reading” hides/removes the item from that Home surface without:

- marking all chapters unread;
- deleting canonical read state;
- resetting tracking state;
- removing the work from Library.

The underlying canonical history/progress infrastructure may remain for sync, tracking, statistics, and diagnostics.

---

## 22. Home

Home is a **user-composable shell**.

No catalog/discovery rows are configured by default.

Continue Reading is the only automatic system section, and it appears only when needed.

The Home engine supports future composition such as:

~~~text
Hero
Continue Reading
Trending
Popular
Recently Updated
custom Collection
custom folder/list output
future Add-on-provided sections
~~~

### 22.1 Collections

Existing Tsuzuki Collections, Folders, Lists, QueryPlanner, caching, and provider capability work should be reused.

The current hardcoded Kitsu provider registry must become capability-driven.

The advanced Collection/Home editor lives in Settings.

Home consumes the user’s composition; Home itself is not the configuration surface.

### 22.2 Future extensibility

Future Add-ons may contribute Home/discovery sources once the corresponding capability contracts exist.

The initial implementation does not require this capability to be available for Mihon content Add-ons.

---

## 23. Search

Search replaces Browse as the discovery destination.

### Empty query

Search shows:

- recent searches;
- Discover content configured from active Integration capabilities.

Initial Discover building blocks:

- Trending;
- Popular;
- Recently Updated / equivalent provider feed.

These are future-customizable using the same collection/list composition model.

### Typed query

Search calls enabled SearchProvider capabilities, aggregates results, and deduplicates using canonical identity rules.

No source-selection step appears before search.

No Add-on/source catalog is exposed from Search.

### No provider

If there is no enabled SearchProvider capability, Search shows a setup empty state linking to Integrations.

---

## 24. Library

Library is a shelf of canonical works.

Cards/items must not expose:

- SOURCE_ONLY;
- reading source ID;
- preferred source;
- per-title language;
- source order;
- source representation settings.

Library keeps:

- categories;
- Library status such as Reading / Completed / On Hold / Dropped;
- search/filtering;
- canonical cover/title/status presentation.

Statuses may drive filters but should not clutter every card by default.

Selecting a Library item opens the canonical work detail screen.

The chapter list is where reading begins.

---

## 25. Work detail screen

The canonical detail screen owns:

- canonical metadata;
- Library add/remove/status actions;
- canonical chapter list;
- provisional chapter indicator;
- chapter read/progress state;
- download state;
- other compact canonical actions.

It does not expose provider configuration as a normal section.

Initial scope deliberately excludes large recommendation/related-media surfaces.

Those can arrive later through capabilities without changing the canonical model.

---

## 26. Settings model

Target high-level organization:

~~~text
Settings
├── Account
├── Integrations
├── Add-ons
│   ├── Installed
│   ├── Repositories
│   └── Updates
├── Home & Collections
├── Reading
│   ├── preferred languages
│   └── automatic fallback
├── Downloads
├── Sync
└── Appearance / other app settings
~~~

### Integrations

Each Integration exposes:

- enabled/disabled state;
- connection/auth state when required;
- capability-specific options;
- metadata/ratings/tracking toggles as appropriate.

### Add-ons

Initial Add-on management is intentionally simple:

- repositories;
- installed Add-ons;
- available updates;
- enable/disable/uninstall;
- per-Add-on settings only when the runtime supports them.

No primary UI for browsing the Add-on’s source catalog exists.

---

## 27. Supabase sync architecture

Google Drive sync is replaced by a server-backed local-first protocol using Supabase Auth + Postgres/RPC.

This is not a “remote database instead of local database” redesign.

SQLDelight remains the local operational source of truth.

~~~mermaid
flowchart LR
    A["Local user action"] --> L["SQLDelight transaction"]
    L --> O["Sync outbox / pending mutation"]
    O --> P["Idempotent Supabase RPC"]
    P --> S["Postgres materialized state"]
    P --> E["Append-only sync events"]
    E --> D["Delta pull by cursor"]
    D --> L
~~~

### 27.1 Why the current Drive journal protocol is not the target

The Drive v2 replica-journal design correctly compensates for Drive’s lack of the transactional semantics needed by multi-device mutable state.

Supabase/Postgres provides a better place to enforce atomic mutation, uniqueness, authorization, and idempotency.

The following Drive-specific complexity should not survive as permanent product architecture:

- Drive file IDs and appDataFolder discovery;
- per-device physical journal shards;
- Drive appProperties;
- Drive revision tokens;
- manifest compatibility;
- Drive-specific create/PATCH retry semantics;
- remote journal file materialization.

The **domain guarantees** developed during that work remain valuable.

### 27.2 Sync guarantees to preserve

- local action succeeds independently of cloud availability;
- retries are safe;
- mutation delivery is at-least-once, not assumed exactly-once;
- duplicate mutation delivery is idempotent;
- independent field changes can merge;
- conflicting concurrent edits are explicit rather than silently overwritten;
- deletes propagate;
- cancellation propagates;
- no cloud error rolls back local user state;
- caches/downloads/recomputable provider inventories are excluded.

### 27.3 Server mutation protocol

Each local sync mutation batch receives a stable client-generated mutation ID.

A push includes conceptually:

~~~text
mutationId
originClientId
domain
baseCursor
operations[]
~~~

The database enforces uniqueness on mutationId per user so retrying the same intent cannot apply it twice.

The server applies the batch transactionally.

For field-aware domains, the server can detect whether a targeted field changed after baseCursor:

- unchanged since base: apply;
- concurrently changed to equivalent value: collapse;
- concurrently changed to a different value: record explicit conflict;
- independent fields: merge;
- delete versus concurrent edit: explicit delete/edit conflict.

This preserves the strongest useful properties of the current causal sync work without needing per-device Drive journal files.

### 27.4 Delta pull

The server emits monotonically ordered per-user sync events.

Clients retain a cursor per sync domain or an equivalent safe cursor strategy.

Pull asks for events after the last accepted cursor and applies them locally.

Initial bootstrap may use a server snapshot followed by delta consumption.

### 27.5 Outbox

The current local outbox philosophy remains.

A network failure leaves pending work dirty.

Pushing remote state must never be required before local UI updates.

### 27.6 Sync domains

Expected synchronized durable user state includes:

- canonical Library membership/status/categories;
- canonical reading progress;
- Continue Reading hide/acknowledgment state;
- new-chapter acknowledgment state;
- Collections/Folders/Lists/Home composition;
- preferred content Add-on per title;
- non-secret Integration settings;
- non-secret Add-on configuration where safe;
- canonical user overrides required for identity/chapter consistency.

Expected exclusions:

- page/image cache;
- downloaded chapter payloads;
- transient content-option cache;
- recomputable provider inventories;
- torrent payloads;
- executable Add-on APK bytes;
- extension trust decisions;
- authentication secrets unless separately designed.

### 27.7 Add-on sync security

Cloud state may describe that a user used or wants a particular Add-on.

It must not cause executable third-party code to be silently installed or trusted.

On a new device, APK-based Add-ons can appear as “restore/install available” and require the normal local trust/install flow.

### 27.8 RLS and authorization

Every user-owned remote row is scoped to the authenticated Supabase user.

Row Level Security is mandatory.

All RPCs must verify auth.uid() / ownership correctly.

Service-role keys never appear in the mobile client.

Security tests must prove that one user cannot read/write another user’s Library, progress, collections, preferences, or sync events.

---

## 28. Canonical identity under multi-device sync

Sync must not regress canonical identity safety.

Required invariants:

- canonical IDs are Tsuzuki IDs, not Kitsu/MAL/source IDs;
- verified external identities are unique within the relevant account identity graph;
- concurrent devices creating representations of the same verified external work must converge without title-string merging;
- any canonical re-key/merge operation must preserve Library membership, chapter progress, history, downloads metadata, categories, tracking mappings, and preferences;
- display title is never a unique key.

The implementation plan must include explicit adversarial tests for concurrent canonical materialization.

---

## 29. Migration from current bootstrap

The migration is additive and staged.

### Preserve

Keep and evolve:

- CanonicalTitle;
- external identities;
- CanonicalChapter;
- ChapterVariant semantics;
- canonical Library;
- canonical progress/history;
- Collections engine;
- Kitsu client code;
- existing MAL client/tracker code;
- Reader;
- local loaders;
- downloader as transitional backend;
- ExtensionManager / extension stores / SourceManager;
- chapter label parser/reconciliation logic;
- sync adapters/outbox concepts that are provider-independent.

### Replace or generalize

- CatalogProvider singleton → Integration capability registry;
- Kitsu hardcoded Home/Search → Integration-driven providers;
- SourceRepresentation → transitional adapter toward ContentBinding;
- source preference model → per-title preferred Add-on;
- chapter source inventory as authority → ChapterEvidence reconciliation;
- Mihon-only reader materialization → provider-neutral prepared content boundary;
- source-bound downloads → CanonicalDownloadArtifact;
- DriveSyncTransport → Supabase sync client/RPC layer.

### Remove from target UX

- Updates tab;
- History tab;
- Browse tab;
- Migration UI;
- source-specific catalog browsing;
- Reading Sources configuration;
- per-title language configuration;
- source IDs/identity state on Library cards.

### Transitional compatibility

Legacy Mihon paths may remain temporarily to protect existing local state and working Reader/download behavior.

They must not become required public concepts in the new UI.

---

## 30. Google Drive migration policy

Google Drive is not part of the target product.

The local database is sufficient to bootstrap a user’s state into Supabase after authentication.

If Tsuzuki has no production users whose only surviving state exists in Drive, no cloud-to-cloud migration mechanism should be built merely to preserve development protocol history.

If production/user data in Drive must be preserved, that requirement becomes a separate one-time import design with explicit acceptance criteria. It must not keep Drive as a permanent dual-sync backend.

---

## 31. Failure behavior

### Integration failure

- Search/discovery provider failure is isolated to that provider;
- another enabled Integration may still respond;
- existing Library works remain accessible from local state;
- provider failure never deletes canonical metadata/chapters.

### Add-on failure

- one failing Add-on does not block other content options;
- preferred Add-on failure with fallback disabled opens selector with remaining results if any;
- no options returns an explicit empty state with retry and Add-ons navigation;
- downloaded local artifact remains usable.

### Sync failure

- local action remains intact;
- mutation stays pending;
- user can continue reading offline;
- typed sync diagnostics record auth/network/conflict/backend failures.

### Conflicts

A true concurrent user-state conflict must not be silently resolved with timestamps merely for convenience.

Conflict policy is domain-aware and testable.

---

## 32. Security boundaries

Treat the following as distinct trust boundaries:

### Integrations

First-party code, but external API responses remain untrusted input and must be parsed/validated.

### Mihon Add-ons

Executable APKs with extension-signature/trust semantics.

Continue using explicit trust handling.

### Remote Add-ons

Remote responses are untrusted network data.

Manifest/resources are schema-validated.

Remote manifest support must not imply arbitrary downloaded code execution.

### Torrent inputs

Magnet/info-hash/file metadata are untrusted.

Paths/file indexes/archives require validation against traversal and malformed archive risks before Reader consumption.

### Supabase

RLS is mandatory.

RPCs are authorization boundaries.

No service-role secrets in client.

Sensitive tokens are not logged or included in generic sync blobs.

---

## 33. Observability and diagnostics

The architecture should preserve useful diagnostics without exposing implementation clutter to normal users.

Recommended internal diagnostics:

- enabled Integration capabilities;
- installed/enabled Add-on capabilities;
- ContentBinding state;
- chapter evidence provenance and reconciliation state;
- content resolution attempts/result timing;
- preferred provider/fallback decision;
- sync pending count;
- last successful sync cursor/time;
- typed sync conflict count;
- Add-on/update probe failures.

Debug logs must avoid OAuth/access tokens, Supabase credentials, private URLs containing secrets, and sensitive provider configuration.

---

## 34. Required acceptance scenarios

The design is not considered implemented until automated and human smoke coverage includes at least:

1. fresh install has no enabled Integration, no Add-on, and no configured Home feed;
2. Search without SearchProvider shows configuration CTA;
3. enabling Kitsu allows Kitsu-backed Search without making Kitsu the canonical identity;
4. enabling a second SearchProvider can produce one canonical work when verified identity evidence proves equivalence;
5. title equality alone does not merge two unknown works;
6. adding a work creates one canonical Library item with no source settings visible;
7. a reported chapter count alone creates zero synthetic CanonicalChapters;
8. Integration chapter evidence creates confirmed chapters;
9. Add-on-only new chapter evidence creates a provisional chapter with visible indicator;
10. later Integration evidence promotes that provisional chapter without losing progress;
11. ambiguous 12.5 / Extra-style evidence is not force-merged at low confidence;
12. first chapter read with no provider preference opens selector;
13. selector shows Add-on, language, scanlation group, and date when present;
14. selecting MangaDex stores Dandadan → MangaDex only for that title;
15. next available chapter on MangaDex opens directly;
16. preferred Add-on missing + automatic fallback off opens selector;
17. preferred Add-on missing + automatic fallback on chooses a deterministic valid fallback;
18. manual fallback selection asks before changing title preference;
19. Reader “Change source” opens alternatives for the same CanonicalChapter;
20. preferred language changes ordering but does not hide other languages;
21. Library-only title never appears in Continue Reading;
22. actual reading progress does appear in Continue Reading;
23. “Remove from Continue Reading” preserves read/progress state;
24. +3 new chapter badge decrements as new chapters are read;
25. no Updates/History/Browse/Migration primary destinations remain;
26. Local Content participates in the same chapter resolver;
27. chapter download uses preferred Add-on or selector if unavailable;
28. downloaded chapter stays readable after the originating Add-on is uninstalled;
29. removing an Add-on preserves historical provenance/mappings needed by existing user state;
30. no content option found presents retry + Add-ons navigation;
31. Supabase account uses native Supabase login/registration, not Google;
32. offline Library/progress mutation succeeds immediately;
33. reconnecting syncs pending state;
34. retrying the same remote mutation does not duplicate it;
35. two devices editing independent fields merge;
36. two devices editing the same field incompatibly produce an explicit conflict;
37. one user cannot access another user’s synced rows under RLS;
38. synced state cannot silently trust/install an executable Add-on;
39. Home remains empty of catalog rows until the user configures them;
40. Continue Reading can appear automatically even when Home has no configured discovery sections.

---

## 35. Non-goals for the first architectural implementation

The first implementation of this architecture does not require:

- full visual polish/redesign completion;
- recommendations/related titles;
- automatic global source fallback unless user enables it;
- per-title language settings;
- source catalog browsing;
- preserving migration as a product concept;
- progressive torrent image streaming;
- binary compatibility with arbitrary existing Stremio Add-ons;
- syncing download payloads;
- syncing Integration OAuth secrets;
- auto-installing executable Add-ons from synced state;
- a default preconfigured Home;
- a default enabled Kitsu/MAL Integration.

Architecture correctness and functional minimal UI come before visual polish.

---

## 36. Recommended delivery boundaries

This is not the implementation plan, but it defines safe architectural boundaries for the later plan.

### Boundary A — Cloud foundation

- Supabase Auth;
- local-first Supabase sync transport/protocol;
- Drive removal from target path;
- RLS/security tests.

### Boundary B — Capability foundation

- IntegrationRegistry;
- AddonRegistry;
- capability contracts;
- provider state/configuration;
- no forced UI redesign yet.

### Boundary C — Integrations

- Kitsu adapter into Integration capabilities;
- MAL adapter into Integration capabilities;
- Search aggregation;
- metadata/ratings provenance.

### Boundary D — Add-on facade

- Mihon Extension Add-on runtime;
- repository/install/update Settings UX;
- internal sources hidden;
- Local Content adapter.

### Boundary E — Chapter authority inversion

- ChapterEvidence;
- reconciliation/promotion/conflict model;
- provisional indicator;
- update observation state.

### Boundary F — Content resolver

- ContentBinding;
- ContentOption;
- per-title preference;
- selector;
- global automatic fallback;
- Reader change-source action.

### Boundary G — Product shell

- Home/Search/Library/Settings navigation;
- empty-by-default Home;
- configurable Collections/Home;
- removal of old tabs and migration/source settings UX.

### Boundary H — Canonical delivery

- Canonical downloads;
- provider-neutral Reader preparation;
- remote Add-on protocol;
- torrent-backed delivery.

The later implementation plan should decompose these boundaries into independently testable work cards and avoid a single “big bang” refactor.

---

## 37. Architecture decisions summary

| Decision | Chosen direction |
|---|---|
| Product model | Nuvio-like modular manga runtime |
| Core identity | Tsuzuki-owned canonical work/chapter |
| Fresh install | Empty/unconfigured |
| Navigation | Home / Search / Library / Settings |
| Catalog/metadata | User-enabled Integrations |
| Initial Integrations | Kitsu, MAL adapters |
| Content | Add-ons |
| Initial Add-on runtime | Mihon extensions |
| Source catalogs | Hidden from product UX |
| Migration | Removed as product concept |
| Chapter authority | Evidence-driven canonical graph |
| Add-on new chapters | May create provisional chapters |
| chapterCount | Metadata only; never synthesize structure |
| First content choice | Selector |
| Remembered provider | Per title |
| Automatic fallback | Global opt-in, off by default |
| Language | Global ordering preference only |
| Local content | Same resolver model |
| Downloads | Canonical chapter-owned |
| Torrent | Delivery mechanism returned by Add-on |
| Home | User-composable, no discovery rows by default |
| Continue Reading | Automatic after real reading |
| Updates | Badges/state, no tab |
| History | Internal state, no tab |
| Tracking | Optional Integration capability |
| Cloud auth | Supabase native login/registration only |
| Google login | Not supported |
| Google Drive | Replaced |
| Cloud sync | Supabase + local-first SQLDelight |
| Executable Add-on restore | Never auto-trust/install from sync |
| Visual polish | After architecture/functionality |

---

## 38. Current-code evidence map

The design intentionally builds on current bootstrap seams rather than assuming a rewrite.

Relevant existing Tsuzuki areas inspected for this design include:

- domain/.../tsuzuki/model/CanonicalTitle.kt
- domain/.../tsuzuki/model/SourceRepresentation.kt
- domain/.../tsuzuki/chapter/model/CanonicalChapter.kt
- domain/.../tsuzuki/chapter/model/ChapterVariant.kt
- domain/.../tsuzuki/chapter/interactor/ReconcileChapterInventory.kt
- domain/.../tsuzuki/chapter/interactor/SelectChapterVariant.kt
- domain/.../tsuzuki/reader/interactor/PrepareCanonicalChapterForReader.kt
- domain/.../tsuzuki/reader/service/CanonicalReaderGateway.kt
- app/.../tsuzuki/MihonCanonicalReaderGateway.kt
- app/.../tsuzuki/MihonReadingSourceGateway.kt
- app/.../extension/ExtensionManager.kt
- domain/.../source/service/SourceManager.kt
- data/.../tsuzuki/kitsu/KitsuCatalogProvider.kt
- domain/.../tsuzuki/collections/*
- data/.../tsuzuki/collections/*
- app/.../ui/reader/loader/*
- domain/.../tsuzuki/sync/*
- app/.../tsuzuki/drivesync/*

Observed current constraints motivating this spec:

- Kitsu is wired as a concrete catalog provider in several paths rather than a user-enabled Integration capability.
- current CollectionQueryProviderRegistry is Kitsu-specific;
- current chapter inventory reconciliation is provider/source-driven;
- current SourceRepresentation/ChapterVariant persistence contains Mihon-specific source identity;
- current canonical Reader compatibility boundary is a strong seam worth preserving;
- Reader already supports HTTP, archive, directory, EPUB, and downloaded loaders;
- Mihon extension repository/install/trust infrastructure already provides most of the first Add-on package lifecycle;
- current Drive sync has evolved into replica journals to compensate for shared-file concurrency limitations.

---

## 39. Nuvio / Stremio reference lessons

The design borrows principles, not implementation blindly.

Useful Nuvio patterns inspected:

- Supabase-backed sync separated from local state;
- client identity on mutations;
- snapshot + incremental delta sync for Library/progress;
- first-party metadata/rating integrations separate from Add-ons;
- configurable Home catalog/collection composition;
- manual/automatic stream selection;
- repository-backed plugin/add-on concepts;
- separate P2P engine rather than embedding torrent concerns into media identity.

Useful Stremio protocol principle:

- declarative Add-on manifest;
- resource/capability declaration;
- remote HTTP contract rather than assuming arbitrary downloaded code execution.

Tsuzuki deliberately differs where manga semantics require it:

- CanonicalChapter and chapter evidence replace episode/video identity;
- content options may be page lists, archives, local artifacts, or torrents;
- chapterCount never substitutes for chapter structure;
- Mihon extensions require an executable/trust runtime different from remote manifest Add-ons.

Reference repositories/documentation used during design:

- https://github.com/NuvioMedia/NuvioMobile
- https://github.com/NuvioMedia/NuvioTV
- https://github.com/NuvioMedia/nuvio-engine
- https://github.com/Stremio/stremio-addon-sdk
- https://github.com/hummingbird-me/api-docs

---

## 40. Resolved deployment policies

The remaining deployment-policy questions are now decided.

### 40.1 Account optionality

A Tsuzuki account is **optional**.

The application must remain usable locally without authentication:

- Library;
- Reader;
- local progress;
- local downloads;
- local Add-ons;
- local Integrations that do not require Tsuzuki cloud identity;
- local Collections and settings.

Supabase authentication is required only for Tsuzuki cloud-backed features such as cross-device synchronization.

The product must not block first launch or local reading behind account creation.

### 40.2 Google Drive retirement

No Google Drive cloud-data importer will be built.

Google Drive sync was used only during pre-production/development validation and has no public user population whose remote-only data must be preserved.

Migration policy is therefore:

1. existing local SQLDelight state remains authoritative on the device;
2. after optional Supabase account creation/login, local synchronizable state bootstraps into Supabase;
3. Drive sync code is retired from the target path;
4. no dual-write, cloud-to-cloud migration, or permanent Drive compatibility layer is required.

This is intentionally a clean protocol cut rather than a compatibility burden carried into the new architecture.

---

## 41. Definition of architectural success

The redesign succeeds when a future developer can remove or replace Kitsu, MAL, MangaDex, the Mihon extension runtime, Supabase, or a torrent engine **without redefining what a manga, chapter, Library entry, reading position, or downloaded chapter means inside Tsuzuki**.

At that point:

> **Tsuzuki is the shell, canonical runtime, Reader, and user-state system. External systems provide capabilities. None of them own the manga.**
