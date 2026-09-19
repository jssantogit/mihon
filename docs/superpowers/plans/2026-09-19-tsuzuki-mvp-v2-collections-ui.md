# Tsuzuki MVP-V2 Collections UI + Import/Export — Milestone 9

Branch: `tsuzuki/mvp-v2-collections-ui`

Baseline: `cad129ac1d3eebd7554c31f70672dd2c7b2f60c3`

## Goal

Expose the accepted Milestone 8 Collections engine through user-facing management while preserving the provider-neutral query model and upstream-friendly Mihon boundaries.

## Block I — Portable JSON v1 ✅

- Domain portable document contract.
- Versioned JSON codec.
- Deterministic encoding.
- Full Collection / Folder / List graph.
- Query AST preserved without provider-specific leakage.
- Fail closed on unsupported schema versions and malformed references.

## Block J — Import / Export Interactors

- Export active user-authored Collections.
- Import as user-owned definitions.
- Preserve IDs when available.
- Remap colliding IDs safely.
- Remap nested folder parents and List references.
- Validate the complete document before persistence.
- Never overwrite SYSTEM definitions implicitly.

## Block K — Collections Management UI

- Collections overview.
- Folder/subfolder hierarchy.
- Lists within folders.
- Create / rename / reorder / enable / disable / duplicate / delete.
- Built-in SYSTEM content distinguishable from USER content.
- Query summary visible without exposing provider internals.
- No query-builder overreach beyond semantics supported by the current AST.

## Block L — Import/Export UI + Navigation

- User-facing import/export actions.
- Android document picker for portable JSON.
- Safe error/success states.
- Entry point from existing Tsuzuki/Mihon navigation without replacing upstream tabs.
- APK checkpoint for physical-device validation.

## Acceptance

- Fast CI green after each block.
- Full Verify at end of Milestone 9.
- APK only at the final UI/device checkpoint.
- No Reader / CanonicalChapter / Drive / Google-auth changes.
- No destructive rewrite of Mihon navigation infrastructure.
