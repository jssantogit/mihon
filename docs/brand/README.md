# Tsuzuki visual identity

The canonical Tsuzuki mark is the three-form negative-space **T** in [`tsuzuki-mark.svg`](./tsuzuki-mark.svg).

## Brand v3 — current mark

The current mark is built from three independent organic masses: one upper arch, one lower-left mass, and one lower-right mass. The negative space between them creates a stylized **T**; the T is never drawn as a separate object.

The geometry is intentionally slightly organic rather than perfectly mathematical. All official variants preserve the exact master paths; only scale, color, background and framing may change.

The former B1 continuous-page mark is historical and remains in the repository only as legacy design evidence. It is no longer the current Tsuzuki master mark.

## Monochrome core

Tsuzuki remains monochrome-first.

- Tsuzuki Ink: `#0B0C0D`
- Tsuzuki Paper: `#F5F3EC`
- Tsuzuki Graphite: `#242628`
- Tsuzuki Ash: `#7D8185`
- Tsuzuki Mist: `#C9CCCE`

The primary signature is Paper on Ink. The inverse signature is Ink on Paper.

## Official SVG variants

- `tsuzuki-mark.svg` — canonical Ink master on transparency.
- `tsuzuki-mark-paper.svg` — Paper master on transparency.
- `tsuzuki-signature-dark.svg` — Paper on Ink.
- `tsuzuki-signature-light.svg` — Ink on Paper.
- `tsuzuki-repo-logo.svg` — repository/README signature.
- `tsuzuki-adaptive-foreground.svg` — 45% adaptive foreground reference.
- `tsuzuki-monochrome.svg` — 45% Android themed-icon reference.
- `tsuzuki-splash.svg` — splash reference.

## Surface rules

- Do not add text, strokes, shadows, gradients, glow or decorative outlines to the main mark.
- Preserve the three master paths and the negative-space T across every variant.
- Android adaptive/monochrome starts at **45% visible width**. This remains an optical calibration target until physical-device smoke; if it changes, adjust only surface scale.
- The Android splash uses Paper on Ink and follows native SplashScreen sizing rules.
- In-app surfaces may use a different container size while preserving the master paths.
- The mark has been checked at 16, 20, 24, 32, 48, 96 and 144 px; no separate small-size geometry is currently required.

## Tsuzuki Rating relationship

Tsuzuki Rating is the chromatic extension of the main mark: the same core concept may appear in RGB with the radial rating halo. The main application mark itself remains monochrome and halo-free.
