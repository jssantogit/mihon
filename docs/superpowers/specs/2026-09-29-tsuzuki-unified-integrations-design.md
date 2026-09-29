# Unified integrations architecture

Date: 2026-09-29
Status: Approved direction / implementation foundation

## Goal

Replace the product-level distinction between Integrations and Tracking with one Integrations surface. Tracking is a capability of an external service, not a separate kind of service.

The internal provider and tracker contracts remain separate. UI unification must not collapse metadata, reading-content, transport, identity, or tracking authority.

## Product model

Settings > Content exposes one Integrations destination.

Integrations are grouped for presentation only:
- Metadata and services: Kitsu, MyAnimeList, MangaUpdates, MangaBaka, Bangumi, Shikimori, Hikka.
- Personal servers: Komga, Kavita, Suwayomi.
- Restricted/compatibility: AniList while broader use is not approved.

Each provider page is provider-specific. Options are shown only when they cause a real supported behavior.

Global tracking behavior (automatic updates, update-on-read) moves into an Integrations-level tracking behavior section. Login, logout, API credentials and provider-specific behavior live on the provider page.

## Capability model

Capabilities are granular and independent:
- SEARCH
- DISCOVERY
- METADATA_BASIC
- METADATA_ARTWORK
- METADATA_EDITORIAL
- METADATA_STAFF
- RATINGS
- RELATIONS
- CROSSWALK
- TRACKING
- USER_LISTS
- REMOTE_LIBRARY
- READING_CONTENT
- DOWNLOADS

Existing SearchProvider, DiscoveryProvider, MetadataProvider, RatingsProvider, TrackingProvider and tracker implementations remain valid internal contracts.

## Policy model

Technical capability never implies permission to use a capability. Each provider/capability must carry a policy state:
- ALLOWED
- ALLOWED_WITH_ATTRIBUTION
- USER_OWNED_DATA
- COMMERCIAL_RESTRICTION
- PERMISSION_REQUIRED
- UNVERIFIED

Restricted or unverified capabilities must fail closed and cannot be selected by global metadata/catalog resolution.

Policy metadata can also record attribution requirements, caching requirements, rate-limit notes and credential/auth requirements.

This is a product engineering compliance guard, not legal advice.

## Provider baseline

### Kitsu
Public catalog/metadata plus account/tracking. Search, discovery, metadata and tracking are legitimate separate dimensions. Do not expose custom API credentials until an actually supported application-registration flow is verified.

### MyAnimeList
Search/metadata/ratings plus OAuth tracking. Application credentials and user authorization are separate. Support custom application credentials only when the official flow permits it and secrets can be handled appropriately on a mobile client.

### MangaUpdates
First-class manga-oriented metadata/search and account/tracking candidate. Attribution, caching and responsible request spacing are requirements.

### MangaBaka
Useful for cross-provider identity/crosswalk and permitted enrichment. Never use aggregation to bypass an upstream provider's terms. Preserve upstream provenance. Commercial/licensing restrictions must remain visible to policy.

### Bangumi
Search/details plus account/tracking candidate. Treat experimental endpoints as such and preserve attribution/copyright requirements.

### Shikimori
Search/metadata plus OAuth tracking candidate. Final capabilities remain gated until current terms and API requirements are verified.

### Hikka
Search/details plus OAuth/read-list tracking candidate. Final capabilities remain gated until current terms and API requirements are verified.

### AniList
The API is technically capable of rich manga metadata and tracking, but broader use in a competing manga/anime tracking product is policy-restricted under the reviewed terms. Do not promote AniList into the Tsuzuki global catalog or enrichment resolver without explicit permission/changed terms. Do not bypass this restriction through aggregators.

### Komga
Personal-server integration. Remote library, server metadata, artwork and progress are user-owned/server-scoped capabilities; it is not a global editorial catalog.

### Kavita
Personal-server integration. Remote library, server metadata, artwork and progress are user-owned/server-scoped capabilities; it is not a global editorial catalog.

### Suwayomi
Personal reading/content server. Remote sources/library, metadata, chapters, reading state and downloads are server-scoped capabilities. It is not a global editorial metadata authority.

## Credentials

There is no universal "own API" form.

Provider pages choose among OAuth, public API, username/password, application Client ID, API key/auth key, or server URL according to the provider. Secrets that cannot safely be held by a public mobile client must not be exposed as cosmetic configuration.

## Provenance

Enriched fields must retain provider provenance. A future resolved title can know independently that artwork came from Kitsu, synopsis from MangaUpdates, rating from MAL, authors from another allowed provider, and external IDs from a crosswalk source.

Provenance is required for attribution, conflict debugging, policy enforcement and deterministic fallback.

Editorial chapterCount remains metadata only and never becomes chapter inventory or reading authority.

## Identity and precedence

Never merge canonical works by title equality. Crosswalk identifiers may provide evidence, but ambiguous identity fails closed.

Multiple enabled metadata providers require deterministic field-level precedence/fallback. Do not implement last-provider-wins behavior.

## Migration

Existing tracking sessions and credentials must be preserved. Removing the Tracking destination must not log users out or change tracker IDs.

Existing IntegrationSettings(enabled, configJson) remains persistence-compatible during the foundation migration. Typed provider configuration is layered over configJson before any storage migration is considered.

## Validation

No local Gradle. All Android/Gradle validation runs in GitHub Actions.

Required tests:
- restricted capabilities cannot enter global resolution;
- provider manifests expose only declared capabilities;
- legacy tracker sessions remain addressable by existing tracker IDs;
- typed config round-trips through existing persistence;
- metadata provenance survives resolution/fallback;
- editorial chapter counts never become chapter inventory.
