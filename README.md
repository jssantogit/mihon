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

Tsuzuki is under active development and does not yet have a stable public release channel.

Development APKs are produced by the repository's signed [APK Build workflow](https://github.com/jssantogit/tsuzuki/actions/workflows/apk.yml) and should be treated as pre-release builds.

- Application ID: `app.tsuzuki`
- Minimum Android version: Android 8.0 (API 26)

Historical development builds used `app.mihon`. Android treats `app.tsuzuki` as a separate application, so existing private data does not migrate automatically. Export a compatible `.tachibk` backup from the older install and restore it in Tsuzuki.

## Build from source

```bash
git clone https://github.com/jssantogit/tsuzuki.git
cd tsuzuki
```

GitHub Actions is the authoritative validation environment for the project. See [CONTRIBUTING.md](./CONTRIBUTING.md) and `docs/superpowers/` for the current engineering constraints and implementation plans.

## Upstream and attribution

Tsuzuki is an independent project derived from [Mihon](https://github.com/mihonapp/mihon). Mihon remains a selective technical upstream for Android/Compose, Reader behavior, extension compatibility, security/dependency updates and other reusable fixes.

Existing upstream copyrights, attribution and third-party license obligations are preserved.

## Disclaimer

Tsuzuki does not host manga, comics or other third-party content. Content availability depends on the providers, sources and integrations configured by the user.

## License

[Apache License 2.0](./LICENSE)
