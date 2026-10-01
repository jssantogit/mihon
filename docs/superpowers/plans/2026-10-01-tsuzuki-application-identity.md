# Tsuzuki application identity migration

**Date:** 2026-10-01  
**Status:** implementation in progress

## Decision

Tsuzuki now owns its Android package identity.

- release application ID: `app.tsuzuki`;
- Kotlin/Android namespace remains `eu.kanade.tachiyomi` for source and extension compatibility;
- FileProvider and Shizuku authorities continue to derive from `${applicationId}`;
- legacy `tachiyomi://` / `mihon://` extension and tracker deeplinks remain where they are compatibility contracts;
- `.tachibk` backup/restore compatibility remains;
- new backup filenames naturally derive from `BuildConfig.APPLICATION_ID` and therefore use the Tsuzuki package ID.

## Variant IDs

Official variants derive from `app.tsuzuki`:

- debug: `app.tsuzuki.dev`;
- release: `app.tsuzuki`;
- foss: `app.tsuzuki.foss`;
- nightly: `app.tsuzuki.debug`;
- deva/devb/devc: `app.tsuzuki.deva`, `.devb`, `.devc`;
- generic: `app.tsuzuki.generic`;
- benchmark: `app.tsuzuki.benchmark`.

The former `.tsuzuki.*` suffixes are removed to avoid duplicated IDs such as `app.tsuzuki.tsuzuki.deva`.

## Data migration consequence

Android considers `app.tsuzuki` a different application from historical `app.mihon` builds. Private app data is therefore not migrated automatically. Historical users must export a Tsuzuki/Mihon-compatible `.tachibk` backup before moving and restore it in the new package.

This is an intentional product-identity break, accepted before a stable public Tsuzuki release channel exists.

## Telemetry separation

The inherited `app/google-services.json` belongs to Mihon's Firebase project and is removed.

Official CI/APK lanes no longer pass `-Pinclude-telemetry`. The noop telemetry implementation remains available in the source tree; a future telemetry backend must be Tsuzuki-owned before telemetry can be enabled again.

## README

The repository README adopts a concise Nuvio-like information hierarchy without copying content:

1. centered Tsuzuki A2 lockup;
2. short product statement;
3. repository/build/issues links;
4. Get Tsuzuki;
5. concise product capabilities;
6. development/project status;
7. upstream attribution, disclaimer and license.

## Validation

- identity contract must require `app.tsuzuki`;
- official workflows must reject inherited Mihon telemetry;
- legacy extension/deeplink/backup contracts remain guarded;
- full GitHub Actions CI is required;
- no local Gradle;
- physical APK smoke must confirm package name/data directories and normal extension/provider behavior.

Full CI is explicitly requested for the implementation head.

README final hierarchy: lockup-only hero (no duplicate text H1), Get Tsuzuki, Build from source, Upstream/attribution, Disclaimer and License.
