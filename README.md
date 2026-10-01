# Tsuzuki

<div align="center">

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/tsuzuki-lockup-paper.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/brand/tsuzuki-lockup.svg">
    <img src="docs/brand/tsuzuki-lockup.svg" alt="Tsuzuki" width="460">
  </picture>

  <p>
    A free, open-source Android manga reader built around one unified library.
    <br />
    Bring your own sources. Tsuzuki keeps works, metadata, chapters, ratings and reading progress coherent across them.
  </p>

  [Source code](https://github.com/jssantogit/tsuzuki) · [Development builds](https://github.com/jssantogit/tsuzuki/actions/workflows/apk.yml) · [Issues](https://github.com/jssantogit/tsuzuki/issues)

</div>

## Get Tsuzuki

Tsuzuki is still under active development and does not yet have a stable public release channel.

Development APKs are produced by the repository's signed **APK Build** workflow. They should be treated as pre-release builds.

Current Android identity:

- application ID: `app.tsuzuki`
- minimum Android version: Android 8.0 (API 26)
- package-private data lives under the Android-managed `app.tsuzuki` directories

Historical development builds used `app.mihon`. Android treats the new package as a separate application, so data from those builds does not migrate automatically. Use Tsuzuki's backup/restore flow when moving from an older package.

## What Tsuzuki does

- **Unified library** — works are represented independently of any single source.
- **Source-agnostic reading** — reading sources can change without redefining the work itself.
- **Provider enrichment** — metadata, tracking and native provider ratings can coexist on the same title.
- **Reader continuity** — chapters, progress, downloads and fallback behavior remain tied to canonical work identity.
- **Extension compatibility** — Tsuzuki preserves the useful Mihon/Tachiyomi extension-facing contracts while maintaining its own product identity.

The architecture deliberately separates metadata providers, reading/content providers and transport.

## Development

```bash
git clone https://github.com/jssantogit/tsuzuki.git
cd tsuzuki
```

GitHub Actions is the authoritative project validation environment. The repository's CI covers formatting, tests, compile checks, SQLDelight migrations, backend checks, native packaging and release compilation as applicable.

See [CONTRIBUTING.md](./CONTRIBUTING.md) and the plans under `docs/superpowers/` before making architectural changes.

## Project status

The current development baseline is `main`.

Major completed foundations include canonical work/chapter identity, Reader integration, automatic reading-source discovery, unified Library work, provider integrations, structured diagnostics and Tsuzuki's Brand v3 identity.

Current work continues around UI restructuring, provider enrichment, Collections/catalogs and the next content-runtime layers.

## Upstream and attribution

Tsuzuki is an independent project derived from [Mihon](https://github.com/mihonapp/mihon).

Mihon remains a selective technical upstream for Android/Compose, Reader behavior, extension compatibility, security/dependency updates and other reusable fixes. Existing upstream copyrights, attribution and third-party license obligations are preserved.

## Disclaimer

Tsuzuki does not host manga, comics or other third-party content. Content availability depends on the providers, sources and integrations configured by the user.

## License

[Apache License 2.0](./LICENSE)
