# Tsuzuki — Project Identity Implementation Plan

Date: 2026-09-28
Branch: `main`
Spec: `docs/superpowers/specs/2026-09-28-tsuzuki-project-identity-design.md`
Audit: `docs/superpowers/plans/2026-09-28-tsuzuki-identity-audit.md`
Status: authoritative implementation plan for Front 1

## Goal

Implement Tsuzuki branding in controlled waves while preserving Android/package/data/extension compatibility.

## Current status

- I1-I8 are implemented.
- I10 was completed on 2026-09-28: the repository is now `jssantogit/tsuzuki`.
- `main` is the default and authoritative branch.
- The legacy `tsuzuki/bootstrap` branch may remain as historical development context, but new baseline references should use `main`.
- I9 remains a separate distribution/release-channel design task.
- A signed branding APK smoke remains the final human validation for Front 1.

## Constraints

- Do not run Gradle locally.
- Do not change `applicationId = app.mihon` in this front.
- Do not replace the persistent Tsuzuki signing identity.
- Do not rename the `eu.kanade.tachiyomi`, `mihon.*`, or `tachiyomi.*` package trees globally.
- Do not remove legacy `tachiyomi://` or `mihon://` deep links.
- Do not alter canonical title/chapter/Reader architecture.
- Preserve upstream attribution and Apache-2.0 obligations.
- Any code behavior change should receive targeted regression coverage.
- Use GitHub Actions for Gradle validation.

## Work breakdown

### Task I1 — Freeze compatibility contracts

Document and test the contracts that branding work must not change.

Verify in source/tests:

- base applicationId remains `app.mihon`;
- release signing path continues to use the persistent Tsuzuki keystore secrets;
- manifest still accepts legacy extension-store schemes;
- tracker OAuth callbacks remain present;
- Supabase callback remains `tsuzuki://auth`;
- FileProvider/Shizuku authorities still derive from applicationId;
- extension-facing `AppInfo` remains in its existing namespace;
- backup restore compatibility remains untouched.

This task is documentation/test-only unless a missing regression guard is found.

### Task I2 — Replace base product name

Change the base app label from `Mihon` to `Tsuzuki`.

Keep existing lane-specific labels:

- Tsuzuki Dev A
- Tsuzuki Dev B
- Tsuzuki Dev C
- Tsuzuki Generic Test

Consider renaming `rootProject.name` from `Mihon` to `Tsuzuki`.

Do not rename Gradle plugin packages or version catalog identifiers.

Acceptance:

- release/base resources present the app as Tsuzuki;
- package/application ID remains unchanged.

### Task I3 — Basic Tsuzuki visual identity assets

Create/land a minimal Tsuzuki identity kit:

- launcher icon;
- launcher foreground/background/monochrome assets as needed;
- splash mark;
- simple in-app logo mark;
- repository logo asset.

Update `LogoHeader` and any other direct Mihon logo references.

Do not use this task to redesign the rest of the UI.

Acceptance:

- no normal Tsuzuki surface intentionally renders the Mihon logo as product branding;
- adaptive/monochrome launcher behavior remains valid.

### Task I4 — Rewrite README as Tsuzuki

Replace the inherited Mihon README with a Tsuzuki README.

Required sections:

- Tsuzuki name/short description;
- current project status;
- high-level feature set relevant to the current product;
- Android requirement if still accurate;
- development/repository status;
- clear upstream attribution to Mihon;
- license;
- disclaimer.

Remove:

- Mihon download badges presented as Tsuzuki;
- Mihon Discord presented as Tsuzuki support;
- Mihon release badges presented as project releases;
- Mihon website presented as project website;
- Mihon donation/community copy presented as Tsuzuki-owned.

Upstream references may remain in an explicit Credits / Upstream section.

### Task I5 — Fix GitHub contribution surfaces

Update:

- `CONTRIBUTING.md`;
- `.github/ISSUE_TEMPLATE/config.yml`;
- relevant issue templates;
- PR template only where identity references exist;
- funding configuration if it presents Mihon fundraising as Tsuzuki funding.

The repository must not route Tsuzuki users to Mihon support channels as if they were Tsuzuki channels.

If Tsuzuki has no replacement community/support URL, omit the misleading link instead of inventing one.

### Task I6 — Correct support/about branding in app

Audit surfaces that expose Mihon fundraising/community copy.

At minimum, inspect:

- SupportUsScreen;
- donation campaign strings;
- About screen;
- open-source licenses screen;
- logo header.

Desired behavior:

- no Mihon fundraising is presented as Tsuzuki fundraising;
- upstream attribution remains available;
- third-party/open-source licenses remain accessible.

Prefer removing/hiding unsupported Tsuzuki support surfaces over inventing a donation channel.

### Task I7 — Workflow branding cleanup

Classify workflows into:

1. Tsuzuki operational;
2. generic CI;
3. inherited Mihon-only/dead.

Keep the current APK workflow behavior and persistent Tsuzuki signing secrets.

Update non-persistent branding where safe.

For `.github/workflows/release.yml`:

- do not simply flip repository guards;
- either leave explicitly disabled/upstream-only with documentation or replace it later under Task I9 after distribution decisions.

For `.github/workflows/update_website.yml`:

- remove/disable as active Tsuzuki infrastructure because it targets `mihonapp/website`.

Do not rename internal `MIHON_GITHUB_RELEASE` merely for aesthetics unless all call sites are intentionally migrated and tested.

### Task I8 — Add identity regression checks

Add lightweight checks/tests that make accidental identity regression visible.

Candidate checks:

- base app name is Tsuzuki;
- applicationId remains `app.mihon` until a dedicated migration changes the contract;
- legacy schemes remain in manifest;
- Tsuzuki Supabase callback remains;
- no release/public README headline reverts to Mihon.

Prefer static/unit checks that do not require Android instrumentation where possible.

### Task I9 — Distribution identity design

Before public release automation is enabled, decide:

- repository URL (fixed at `jssantogit/tsuzuki`);
- release channel;
- updater behavior;
- artifact naming;
- release signing source;
- where release notes live;
- whether a project website exists;
- whether app updates are GitHub-based or another mechanism.

Only after those decisions:

- implement a Tsuzuki release workflow;
- remove dead Mihon release automation;
- finalize distribution-owned links/workflows against the current repository.

### Task I10 — Repository rename — complete

Completed on 2026-09-28.

Result:

- repository: `jssantogit/tsuzuki`;
- default/authoritative branch: `main`;
- `main` was fast-forwarded to the accepted Tsuzuki baseline before rename;
- project-owned URLs are updated to the new slug;
- the old GitHub slug is treated only as a redirect/compatibility path, not as the canonical project URL.

## Recommended execution order

1. I1 compatibility freeze
2. I2 product name
3. I3 visual identity assets
4. I4 README
5. I5 GitHub contribution surfaces
6. I6 in-app support/about cleanup
7. I7 workflow branding cleanup
8. I8 regression checks
9. Full CI
10. Human APK smoke for branding
11. I9 distribution identity design
12. I10 repository rename

I9/I10 may be postponed without blocking the basic Tsuzuki identity if distribution is not yet ready.

## CI strategy

No local Gradle.

For implementation commits:

- run formatting/test lanes through GitHub Actions;
- use CI v2.1 as the primary gate;
- run release compile where planner requires it;
- generate an APK only when there is a visual identity build worth physically validating.

Do not describe planner-skipped jobs as passed.

## Human smoke checklist

When the first identity APK is produced, verify:

- launcher shows Tsuzuki icon;
- app drawer label is Tsuzuki;
- splash branding is Tsuzuki;
- settings/about header does not show Mihon logo as product identity;
- app still sees existing user data when installed over the current signed Tsuzuki APK;
- extension management still works;
- legacy extension-store links still open;
- at least one tracker OAuth flow still routes correctly if credentials/environment allow;
- Tsuzuki account callback still routes correctly;
- Reader/basic library flow is unaffected.

## Stop conditions

Stop and investigate rather than forcing a rename if:

- Android treats the build as a different installed package;
- existing data becomes inaccessible;
- extension discovery/installation regresses;
- OAuth callback routing breaks;
- backup restore compatibility changes;
- signature verification differs from the accepted Tsuzuki signing identity;
- a rename would require touching canonical content architecture.

## Definition of done

Front 1 is complete when:

- Tsuzuki is the clear product identity in app and repository;
- a basic unique logo/icon set exists;
- no misleading Mihon community/release/fundraising surface is presented as Tsuzuki;
- Mihon remains correctly credited as upstream;
- package/data/signing/deep-link compatibility is preserved;
- CI is green;
- a signed APK passes the branding smoke without functional regression.
