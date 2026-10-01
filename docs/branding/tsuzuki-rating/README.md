# Tsuzuki Rating — approved visual mark

**Status:** Approved  
**Approved:** 2026-10-01  
**Canonical asset:** [`tsuzuki-rating.svg`](./tsuzuki-rating.svg)

This directory records the approved visual identity for **Tsuzuki Rating**.

## Mark

The approved mark uses:

- an RGB/chromatic three-form core;
- a stylized **T** perceived through negative space rather than drawn as a normal letter;
- a multicolor radial halo that represents multiple rating signals converging into a Tsuzuki result;
- no fixed number of halo elements tied to the current number of rating providers.

The Tsuzuki Rating mark is a **chromatic extension of the main Tsuzuki mark**. Its core concept is shared with the official three-form negative-space T; RGB treatment and the radial halo distinguish the rating identity. The main application mark itself remains monochrome-first and halo-free.

## Product meaning

Tsuzuki Rating is the Tsuzuki-owned aggregate score derived from available provider ratings.

The V1 product rules are now defined separately from the visual asset:

- provider-native scores are normalized to a 0–10 internal scale;
- every distinct provider contributes one equal-weight signal;
- at least two providers are required;
- raw vote counts are preserved but do not weight V1;
- verified and strict corroborated rating-only evidence remain distinguishable;
- provider-native ratings remain visible alongside the aggregate.

The implementation specification lives in `docs/superpowers/plans/2026-10-01-tsuzuki-rating.md`.

Provider-native ratings remain individually identifiable and must not be replaced by the Tsuzuki Rating mark.

## Usage rule

Treat `tsuzuki-rating.svg` as the visual source of truth for this mark.

Future platform-specific conversions must preserve the approved core silhouette, negative-space T, halo density and RGB character rather than reinterpret the symbol. The core should remain visually related to `docs/brand/tsuzuki-mark.svg`.

The Android runtime asset at `app/src/main/res/drawable-nodpi/tsuzuki_rating.webp` is extracted directly from the embedded WebP in the canonical SVG, so the application uses the approved artwork without a redraw.
