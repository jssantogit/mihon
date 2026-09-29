# Tsuzuki visual identity integration

Date: 2026-09-28
Status: Brand v2 refinement after first signed APK smoke

## Approved identity

- Master geometry: B1 continuous-page M+T monogram.
- Brand model: monochrome-first, directly referencing manga ink and paper.
- Tsuzuki Ink: `#0B0C0D`.
- Tsuzuki Paper: `#F5F3EC`.
- Tsuzuki Graphite: `#242628`.
- Tsuzuki Ash: `#7D8185`.
- Tsuzuki Mist: `#C9CCCE`.
- Primary signature: Paper on Ink.
- Inverse signature: Ink on Paper.
- No chromatic accent is part of the core brand identity.

## First APK smoke findings

The first signed Tsuzuki branding APK proved that the B1 mark works as a launcher icon and that the installed-package/signing path remains intact.

Two optical refinements were identified from real Android surfaces:

- the About header mark appeared too small because the B1 geometry occupies only part of its vector viewport even inside a 96dp Compose box;
- the adaptive foreground still appeared optically large in some Android system surfaces.

Refinement:

- About header container: 144dp, producing roughly a low-90dp visible B1 mark;
- adaptive/monochrome scale: 46% target after the second signed-APK smoke showed 56% remained optically overfilled on Android system surfaces;
- no geometry change to B1.

## Integration

The inherited launcher foreground, background, monochrome mark, splash mark, in-app logo header, and repository logo use Tsuzuki branding. Existing internal resource names may remain when they are implementation details rather than public product identity.

Brand v2 changes only identity surfaces. It does not recolor the application-wide Material theme; that belongs to the later visual design-system front.

Legacy Jade/Midnight resources may remain temporarily if implementation compatibility requires them, but launcher, splash, repository branding, and other primary identity surfaces must use Ink/Paper.

## Compatibility constraints

Do not change `applicationId`, Android namespace, Mihon/Tachiyomi extension or deep-link contracts, backup compatibility, provider authorities, or signing contracts.

Do not run Gradle locally. GitHub Actions remains authoritative for build validation.
