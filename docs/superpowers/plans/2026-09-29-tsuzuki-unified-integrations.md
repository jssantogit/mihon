# Unified integrations implementation plan

Date: 2026-09-29
Branch: tsuzuki/unified-integrations-foundation

## Phase 0 — prerequisite CI
The branch is based on tsuzuki/ui-post-smoke-fixes. Before merging this work, resolve the existing red CI on that baseline; do not hide or bypass it.

## Phase 1 — foundation
1. Add provider capability and policy models to the Tsuzuki integration domain.
2. Add IntegrationManifest with provider identity, category, capabilities, per-capability policy and compliance notes.
3. Extend DefaultIntegrationRegistry to expose manifests without breaking current IntegrationProvider contracts.
4. Add typed config adapters over existing IntegrationSettings.configJson. No database migration yet.
5. Add provenance model for resolved metadata fields; do not change chapter inventory semantics.
6. Add policy-gate tests, especially AniList fail-closed behavior.

## Phase 2 — unify existing services
1. Register Kitsu and MAL against the new manifests.
2. Adapt MangaUpdates, MangaBaka, Bangumi, Shikimori and Hikka from tracker-only product presentation into integration manifests while reusing inherited tracker implementations.
3. Register AniList as compatibility/restricted; preserve existing session behavior but block restricted global metadata/catalog use.
4. Register Komga, Kavita and Suwayomi as personal-server integrations with USER_OWNED_DATA/server-scoped policies.
5. Preserve tracker numeric IDs and existing credentials.

## Phase 3 — UI migration
1. Remove Monitoring as an independent Settings destination.
2. Build one Integrations hub grouped into metadata/services, personal servers and compatibility/restricted.
3. Move global tracking behavior preferences into an Integrations-level section.
4. Provider pages render only supported, policy-allowed user-facing controls.
5. Reuse existing tracker authentication flows inside provider pages.
6. Kitsu/MAL informational-only detail pages become real configuration surfaces.
7. Add provider-specific credentials only where supported; never create a generic API-key form.

## Phase 4 — product behavior
1. Wire enabled metadata/search/discovery capabilities into catalog surfaces only through policy gates.
2. Implement deterministic field-level metadata precedence and fallback.
3. Preserve field provenance and attribution.
4. Add cache invalidation when provider settings affecting enrichment change.
5. Keep reading sources and editorial metadata providers independent.
6. Never use metadata chapterCount as reading inventory.

## Phase 5 — CI and smoke
1. Run formatting/tests through GitHub Actions only.
2. Fix failures on the branch; never run Gradle locally.
3. Human smoke: existing tracker login persistence, integration hub, provider configuration, Search/metadata enrichment, personal-server configuration.
4. Do not merge while baseline or branch CI is red.
