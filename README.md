# Tsuzuki

<p align="center">
  <img src=".github/assets/logo.png" alt="Tsuzuki logo" width="160" />
</p>

Tsuzuki is an Android manga reader focused on a unified library, source-agnostic reading, and a cleaner separation between metadata, reading sources, and transport.

The project is under active development. There is not yet a stable public Tsuzuki release channel or official website, so development builds and repository workflows should be treated as pre-release infrastructure.

## Current direction

Tsuzuki is evolving beyond a traditional source-bound manga reader. The current codebase includes work around:

- canonical title identity across metadata and reading sources;
- automatic discovery of configured reading sources;
- a unified library and source-resolution flow;
- a configurable Reader inherited from the Mihon/Tachiyomi lineage;
- metadata and tracking integrations;
- local downloads, backups, and synchronization work;
- compatibility with the Mihon/Tachiyomi extension ecosystem where that compatibility remains useful.

The architecture intentionally keeps metadata providers, reading/content providers, and transport as separate concerns.

## Project status

The current development baseline is `tsuzuki/bootstrap`.

Major completed work includes the canonical title/chapter model, Reader integration, automatic source discovery, and chapter-content integrity protections. Current roadmap work is focused on establishing Tsuzuki's own project identity, reorganizing the application UI, improving Collections/catalogs, and later expanding the content runtime.

Development plans and design documents live under:

- `docs/superpowers/specs/`
- `docs/superpowers/plans/`

## Android support

The current build configuration targets a minimum Android SDK level of 26 (Android 8.0).

## Development

Tsuzuki uses GitHub Actions as the authoritative Gradle validation environment.

Project-specific engineering constraints include:

- do not run Gradle locally for project validation;
- prefer regression tests before behavioral fixes;
- preserve canonical identity rather than merging titles by name;
- fail closed when chapter/content identity is ambiguous;
- preserve compatibility contracts deliberately instead of performing global renames.

See `CONTRIBUTING.md` and the current project specs/plans before making architectural changes.

## Upstream and attribution

Tsuzuki is an independent project derived from [Mihon](https://github.com/mihonapp/mihon).

Mihon remains an important technical upstream and compatibility reference. Tsuzuki selectively consumes upstream improvements where they fit the project, while maintaining its own product and architecture decisions.

Mihon itself continues the lineage of Tachiyomi. Existing upstream copyrights, attribution, and third-party license obligations are preserved.

## Disclaimer

Tsuzuki does not host manga, comics, or other third-party content. Content availability depends on the providers, sources, and integrations configured by the user.

## License

This project is distributed under the Apache License 2.0. See [LICENSE](./LICENSE) for the full license text and inherited copyright notices.
