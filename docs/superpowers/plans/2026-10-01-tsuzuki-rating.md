# Tsuzuki Rating — implementation plan

**Date:** 2026-10-01  
**Status:** Waves 1–3 implemented; Wave 3 validation requested

## Product definition

Tsuzuki Rating is an aggregate summary of provider-native community ratings. It never replaces provider ratings or provider branding.

### V1 formula

- normalize every usable provider score to a 0–10 scale:
  `normalized = value / maxValue * 10`;
- every provider contributes exactly one equal-weight signal;
- raw provider vote counts are preserved but **do not weight** the V1 aggregate;
- at least **two distinct providers** are required;
- one provider alone produces no Tsuzuki Rating;
- invalid/out-of-range scores fail closed;
- calculation uses full precision; UI rounds only for presentation.

### Identity evidence

Every contributing source carries one of:

- `VERIFIED` — provider ID already known or proven through provider-published mapping;
- `CORROBORATED_RATING_ONLY` — the existing ephemeral strict match (exact normalized title/alias plus publication year or creator corroboration, unique result only).

Corroborated rating-only evidence may contribute to Tsuzuki Rating but never becomes canonical identity.

## Wave 1 — core

- preserve provider vote counts across exact and supplemental rating paths;
- preserve rating identity evidence into resolved models;
- add `TsuzukiRatingSource` and `TsuzukiRating`;
- add pure deterministic `ComputeTsuzukiRating`;
- expose the aggregate on resolved metadata;
- unit-test normalization, minimum source count, equal weighting, duplicate providers, invalid data and corroborated evidence.

## Wave 2 — product surfaces

- register **Tsuzuki** as a first-party integration under a top-level **General** section;
- expose a global Tsuzuki switch and, inside its detail screen, an independent **Ratings** capability switch;
- Tsuzuki off → no Tsuzuki Rating is calculated or displayed;
- Tsuzuki on + Ratings off → native provider ratings remain available, but no aggregate is produced;
- Tsuzuki on + Ratings on → aggregate when at least two valid provider ratings exist;
- compute Tsuzuki Rating for Search/Discovery catalog items;
- show the approved Tsuzuki Rating identity in canonical Detail;
- show a compact Tsuzuki Rating on catalog cards while retaining provider provenance;
- use the approved RGB asset without changing its design.

## Wave 3 — diagnostics and acceptance

- record bounded aggregate composition diagnostics: source count, verified/corroborated counts, Tsuzuki Ratings enabled state and whether an aggregate was produced;
- never export raw titles, raw score payloads or arbitrary provider payloads;
- sanitizer tests enforce the bounded diagnostic contract;
- unit acceptance covers aggregate source counts from 2 through 6;
- full CI on every wave;
- final signed APK only after all waves merge;
- physical smoke with works exposing 1 through 6 ratings and both Tsuzuki switches.

## Invariants

- native provider scales remain unchanged in provider badges;
- Tsuzuki Rating is always 0–10;
- vote-count dominance is intentionally rejected in V1;
- source identity matching rules are not weakened;
- no canonical identity is created from a rating-only match;
- formula changes require an explicit future product decision;
- Tsuzuki is first-party product functionality, not an external metadata provider; its integration entry exists only to give the user explicit control over Tsuzuki-owned capabilities.
