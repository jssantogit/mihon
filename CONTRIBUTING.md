# Contributing to Tsuzuki

Thanks for your interest in contributing to Tsuzuki.

Tsuzuki is under active architectural development, so changes should follow the current specs and implementation plans rather than assuming that inherited Mihon behavior is always authoritative.

## Before contributing

Review the relevant documents under:

- `docs/superpowers/specs/`
- `docs/superpowers/plans/`

For large or architectural changes, open an issue or discussion in this repository before implementing a competing design.

## Development requirements

The project is primarily Kotlin/Android and expects familiarity with:

- Android development;
- Kotlin;
- Git and GitHub Actions.

### Validation

For this repository:

- **do not run Gradle locally for project validation**;
- Gradle verification is performed through GitHub Actions;
- behavioral fixes should include regression coverage when practical;
- do not disable or skip tests merely to make CI pass;
- do not describe planner-skipped CI jobs as passed.

Small non-Gradle tools used by repository automation may be tested directly when appropriate.

## Tsuzuki-specific engineering rules

Please preserve these project contracts unless an accepted spec explicitly changes them:

- canonical identity must not be inferred by title equality alone;
- metadata providers and reading/content providers remain separate concerns;
- ambiguous chapter/content identity should fail closed;
- Mihon/Tachiyomi extension compatibility should not be broken for cosmetic renaming;
- persistent identifiers such as package IDs, deep links, authorities, backup formats, and signing identity require an explicit migration plan;
- do not perform global `Mihon`/Tachiyomi → Tsuzuki replacements.

## Upstream changes

Tsuzuki is derived from [Mihon](https://github.com/mihonapp/mihon), but it does not attempt to mirror upstream continuously.

Useful upstream changes may be selectively ported when they fit Tsuzuki. A port should be reviewed as a Tsuzuki change, because a valid Mihon change can still conflict with Tsuzuki architecture.

If a bug belongs to Mihon itself rather than Tsuzuki-specific code, contributors may also choose to report or fix it upstream separately.

## Pull requests

A pull request should:

- explain what changed and why;
- reference the relevant issue/spec/plan when one exists;
- include tests for behavioral changes;
- note any compatibility or migration implications;
- include screenshots for visible UI changes when useful;
- have the relevant GitHub Actions checks green.

Keep unrelated refactors out of focused changes whenever possible.

## License and attribution

Contributions are made under the repository's Apache-2.0 license.

Do not remove inherited upstream copyright, attribution, or third-party license information unless the legal basis for doing so is clear and documented.
