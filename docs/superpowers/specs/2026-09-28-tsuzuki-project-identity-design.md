# Tsuzuki — Project Identity Design

Date: 2026-09-28
Branch: `tsuzuki/bootstrap`
Audit baseline: `docs/superpowers/plans/2026-09-28-tsuzuki-identity-audit.md`
Status: authoritative design for Front 1 — Project Identity

## Purpose

Establish Tsuzuki as the public identity of the product without breaking the compatibility contracts inherited from Mihon/Tachiyomi.

This design intentionally separates **product identity** from **technical ancestry and compatibility**.

## Product relationship to Mihon

Tsuzuki is an independent project derived from Mihon.

Mihon remains:

- the historical upstream;
- a source of selective technical improvements;
- an ecosystem compatibility target where relevant;
- a project that must continue to receive correct attribution.

Tsuzuki must not present itself as the official Mihon app, release channel, website, community, or donation campaign.

Future upstream intake is selective rather than continuous synchronization.

## Design principle

The identity migration is asymmetric:

### Public surface

Prefer Tsuzuki.

### Compatibility surface

Preserve existing contracts unless a migration is explicitly designed.

### Internal implementation surface

Do not rename merely for aesthetic consistency.

## Non-goals

This front does not:

- redesign the full application UI;
- replace the design system;
- rename the full Kotlin/Java package tree;
- rename all `mihon.*` or `tachiyomi.*` internals;
- migrate `applicationId`;
- change APK signing identity;
- remove legacy deep links;
- redesign the extension runtime;
- alter canonical title/chapter/Reader architecture.

## Public identity contract

The product-facing name is:

**Tsuzuki**

This name should be used for:

- base app label;
- repository presentation;
- README;
- project documentation written for Tsuzuki;
- GitHub contribution surfaces owned by Tsuzuki;
- release/artifact names owned by Tsuzuki;
- in-app product branding;
- logo/icon/wordmark assets;
- About copy that describes the current product.

The name Mihon may still appear in public UI when it explicitly describes upstream attribution or compatibility.

## Repository identity

The repository should eventually be renamed from `jssantogit/mihon` to a Tsuzuki name.

Repository rename is coordinated after in-repo links and workflows are safe.

The implementation must not assume that GitHub redirect behavior is sufficient for every script or external integration.

## Android identity contract

### App label

The base release app label becomes `Tsuzuki`.

Development lane labels already using Tsuzuki remain as-is unless simplification is separately desired.

### applicationId

`app.mihon` is frozen for this front.

Reason:

- installed-app upgrade identity;
- app sandbox ownership;
- persisted data access;
- permissions and authorities;
- backup filename behavior;
- external integration assumptions.

A future package migration, if desired, requires a dedicated design and cannot be smuggled into branding work.

### Namespace and packages

The following are not branding targets in this front:

- `eu.kanade.tachiyomi.*`;
- `mihon.*`;
- `tachiyomi.*`;
- `mihon.gradle.*`;
- `mihon.plugins.*`.

They stay unless a future technical refactor gives a reason to change them.

## URI and callback compatibility

Existing schemes remain accepted:

- `tachiyomi://add-repo`;
- `mihon://extension-store`;
- `mihon://...` tracker OAuth callbacks.

The existing Tsuzuki Supabase callback remains:

- `tsuzuki://auth`.

A Tsuzuki alias may be added for a legacy function only when doing so does not break existing links or provider registration.

Removal of Mihon/Tachiyomi schemes is out of scope.

## Extension compatibility

Interfaces and namespaces consumed by Mihon/Tachiyomi extensions must remain compatible.

In particular, extension-facing surfaces such as `eu.kanade.tachiyomi.AppInfo` are not renamed for branding.

The product may describe this relationship in UI as compatibility with Mihon/Tachiyomi extensions where useful.

## Signing and upgrade contract

Existing persistent Tsuzuki signing material remains authoritative for test/release APK continuity.

No keystore replacement is allowed in this front.

No change may intentionally break installation over the currently accepted Tsuzuki APK line.

## Backup compatibility

Existing `.tachibk` restore support remains.

Existing backup files remain readable.

Backup export branding may be adjusted only if reading legacy filenames/formats remains unaffected.

The first identity implementation wave does not need to rename the backup filename.

## Visual identity scope

Front 1 must provide only the minimum identity kit required to stop shipping Mihon branding:

- launcher icon;
- splash mark where applicable;
- simple logo mark;
- wordmark/name treatment if needed;
- repository logo.

This is not the final design system.

Color system, typography system, cards, spacing, motion, Reader styling, and application-wide visual language belong to Front 5.

## GitHub and documentation contract

The Tsuzuki repository must not direct contributors/users to Mihon infrastructure as if it were Tsuzuki infrastructure.

The identity migration should update or replace:

- README;
- CONTRIBUTING;
- issue template contact links;
- release naming/copy;
- website dispatch workflow;
- badges;
- repository/logo references.

Upstream links remain where they are explicitly labeled as upstream/reference/credits.

## Support/donation contract

Mihon donation/support copy must not be surfaced as Tsuzuki's own fundraising.

If Tsuzuki has no support/donation channel, that surface should be removed or hidden from the Tsuzuki product.

This does not affect upstream credits.

## About / attribution contract

Tsuzuki must have a clear attribution surface containing the equivalent facts:

- Tsuzuki is derived from Mihon;
- Mihon itself descends from Tachiyomi lineage where relevant;
- upstream copyright and Apache-2.0 obligations are preserved;
- third-party licenses remain available.

The exact prose can evolve, but attribution must not be erased.

## Workflow identity

### APK workflow

The current Tsuzuki artifact naming and persistent Tsuzuki signing secrets are correct and remain.

Internal compatibility names such as `MIHON_GITHUB_RELEASE` are not required to change.

### Legacy release workflow

The inherited Mihon release workflow must not be activated as Tsuzuki by only changing its repository guard.

A Tsuzuki release workflow must have deliberate:

- artifact names;
- release title;
- signing source;
- updater behavior;
- distribution target;
- release notes;
- ownership/secrets.

Until those are defined, the inherited upstream release workflow should remain non-operational or be removed from active project workflows.

### Website workflow

The Mihon website dispatch is not Tsuzuki infrastructure and should not remain an active Tsuzuki workflow.

## Updater contract

Any updater identity/endpoints must be audited before enabling public Tsuzuki releases.

The product must never accidentally query or present Mihon releases as Tsuzuki updates unless intentionally designed as an upstream notification feature, which is not part of this front.

## Migration waves

### Wave 1 — Safe public branding

Allowed:

- base app label;
- repository docs;
- contribution/issue surfaces;
- visible Mihon logo references;
- Tsuzuki logo/icon assets;
- About/credits correction;
- removal of misleading Mihon support/donation presentation;
- root project display name;
- non-persistent artifact/copy cleanup.

Forbidden:

- applicationId;
- signature;
- package/namespace tree;
- legacy callback removal;
- authorities;
- persisted storage identifiers.

### Wave 2 — Distribution identity

After distribution decisions:

- Tsuzuki release workflow;
- Tsuzuki release artifacts;
- update channel/endpoints;
- website/release integration if any;
- repository rename coordination.

### Wave 3 — Optional technical cleanup

Only if justified independently:

- internal variable names;
- build-logic package names;
- theme/class names;
- package cleanup.

Wave 3 is optional and should not be treated as required branding work.

## Acceptance criteria

Front 1 is accepted when:

1. A normal user no longer encounters Mihon as the apparent product identity.
2. Tsuzuki has its own basic icon/logo identity.
3. README/GitHub surfaces identify the project as Tsuzuki.
4. Upstream Mihon attribution is explicit and correct.
5. Mihon donation/community links are not presented as Tsuzuki-owned.
6. Existing installed Tsuzuki APKs can continue along the same upgrade/signing path.
7. Existing local data is not intentionally invalidated.
8. Existing Mihon/Tachiyomi extension compatibility remains.
9. Existing legacy deep links remain accepted.
10. No global package rename is introduced merely for branding.
11. CI remains green after implementation.
12. Gradle is not run locally; validation uses GitHub Actions.

## Safety rules

- No search/replace global rename.
- No local Gradle.
- Behavioral changes need regression coverage where applicable.
- Prefer additive compatibility over breaking replacement.
- Fail closed when an external identity contract is uncertain.
