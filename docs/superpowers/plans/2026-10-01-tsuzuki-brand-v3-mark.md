# Tsuzuki Brand v3 — three-form mark integration

**Date:** 2026-10-01  
**Status:** implementation in progress

## Decision

The former B1 continuous-page mark is superseded as the current Tsuzuki logo by the three-form mark discovered during Tsuzuki Rating exploration.

The new application mark removes the rating halo and all chromatic treatment. It consists of three organic masses whose negative space forms a stylized T.

The main brand remains monochrome-first:
- Ink `#0B0C0D`
- Paper `#F5F3EC`

Tsuzuki Rating remains the chromatic/halo extension of the same visual family.

## Canonical geometry

`docs/brand/tsuzuki-mark.svg` is the new source of truth.

Invariants:
- exactly three masses: top, left, right;
- T exists only through negative space;
- no raster embedding in the master;
- no text, stroke, shadow, glow or gradient in the main mark;
- variants may change only color, background, framing and surface scale.

Small-size checks at 16/20/24/32/48/96/144 px keep the T legible, so no separate optical-size master is currently needed.

## Variants

The repository carries:
- Ink master on transparency;
- Paper master on transparency;
- dark and light signatures;
- repository logo;
- adaptive foreground reference;
- Android monochrome reference;
- splash reference.

## Android integration

Production surfaces use the same three paths in:
- `ic_mihon.xml`;
- `ic_launcher_foreground.xml`;
- `ic_launcher_monochrome.xml`.

The launcher background remains Ink. Splash continues to use the shared in-app mark through `ic_mihon_splash.xml`. About continues to reuse `ic_mihon.xml`.

Initial adaptive/monochrome calibration target: **45% visible width**. This is provisional until physical-device smoke. Calibration changes must alter only scale, never master geometry.

## Legacy

`docs/brand/tsuzuki-mark-b1.svg` remains as historical evidence and is explicitly non-canonical.

Historical Brand v2 plans and records should be read as evidence of the previous identity phase, not as current geometry requirements.

## Validation

- identity-contract guardrails must enforce the new master and 45% initial launcher calibration;
- GitHub Actions remains the authoritative project validation environment;
- do not run Gradle locally;
- full CI is explicitly required before merge;
- after CI is green, produce a signed APK only when physical launcher/splash/About smoke is requested.

## A2 wordmark / repository lockup

The approved repository/About wordmark direction is **A2 — Organic T**.

- lowercase forms remain close to Roboto Medium / Android typography;
- only the initial T is custom, with a softly curved crossbar and organic/tapered stem;
- the wordmark is stored as vector paths, not runtime text;
- repository README uses the horizontal mark + wordmark lockup with Ink/Paper theme variants;
- the in-app About header uses the same lockup geometry through `ic_tsuzuki_lockup.xml` and Compose tinting;
- launcher and splash remain symbol-only.

This creates a deliberate hierarchy: symbol alone for compact/system identity, horizontal lockup for institutional/repository identity, and RGB + halo for Tsuzuki Rating.

Full CI is required before merge; no local Gradle validation.
