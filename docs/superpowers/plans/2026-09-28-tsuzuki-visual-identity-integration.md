# Tsuzuki visual identity integration

Date: 2026-09-28

## Approved identity

- Master geometry: B1 continuous-page M+T monogram.
- Primary: Tsuzuki Jade `#17C7A3`.
- Brand background: Tsuzuki Midnight `#132235`.
- Launcher reference composition: 64%.
- Android adaptive foreground: 61%.
- Android monochrome: same B1 geometry and 61% scale.
- Splash: B1 Jade on Midnight using the native Android SplashScreen.

## Integration

The inherited launcher foreground, background, monochrome mark, splash mark, in-app logo header, and repository logo are replaced visually with Tsuzuki. Existing internal resource names may remain when they are implementation details rather than public product identity. Debug-only launcher overrides are removed so debug and release share the same Tsuzuki mark.

## Compatibility constraints

Do not change `applicationId`, Android namespace, Mihon/Tachiyomi extension or deep-link contracts, backup compatibility, provider authorities, or signing contracts.
