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

The mark is a **Tsuzuki Rating sub-brand**, not a replacement for the main Tsuzuki application logo. The core Tsuzuki brand remains monochrome-first.

## Product meaning

Tsuzuki Rating is intended to become a Tsuzuki-owned aggregate score derived from available provider ratings. The approved icon establishes its visual identity only.

This asset does **not** define or implement:

- the aggregation formula;
- normalization between provider scales;
- minimum provider requirements;
- weighting;
- confidence behavior;
- UI placement or final sizing.

Those product rules must be decided separately before the aggregate rating itself is implemented.

Provider-native ratings remain individually identifiable and must not be replaced by the Tsuzuki Rating mark.

## Usage rule

Treat `tsuzuki-rating.svg` as the visual source of truth for this mark.

Future platform-specific conversions (for example Android VectorDrawable or Compose-native paths) must preserve the approved silhouette, negative-space T, halo density and RGB character rather than reinterpret the symbol.
